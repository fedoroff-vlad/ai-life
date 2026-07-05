package dev.fedorov.ailife.agents.creator.profile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.creator.http.CreatorProfileClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.creator.SetCreatorProfileInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Turns a typed message describing a creator profile into a stored {@code creator_profile} row. One
 * llm-gateway {@code DEFAULT} turn with the {@code creator-profiler} SKILL as the system prompt
 * extracts the track JSON → write via {@code mcp-creator}'s {@code POST /internal/creator-profile}
 * (CR-c1).
 *
 * <p>Scope: the SKILL emits {@code "self"} (the speaker's own track → {@code ownerId = userId}) or
 * {@code "household"} (a shared/brand account → {@code ownerId = null}, the household-default). Any
 * stage failing degrades to a friendly message. Mirrors the nutritionist's {@code DietProfiler}.
 */
@Component
public class CreatorProfiler {

    private static final Logger log = LoggerFactory.getLogger(CreatorProfiler.class);
    private static final String SKILL_NAME = "creator-profiler";

    private final CreatorProfileClient profiles;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public CreatorProfiler(CreatorProfileClient profiles, LlmClient llm, SkillRegistry skills,
                           AgentManifest manifest, ObjectMapper json) {
        this.profiles = profiles;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> setProfile(NormalizedMessage msg) {
        // temperature=0: profile extraction must be deterministic/faithful, not creative.
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(msg.text())), 0.0);
        return llm.chat(request)
                .flatMap(r -> write(msg, r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("creator-profiler failed: {}", e.toString());
                    return Mono.just(reply("Не удалось обновить профиль создателя. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> write(NormalizedMessage msg, String llmContent, String model) {
        JsonNode draft = parseDraft(llmContent);
        if (draft == null) {
            return Mono.just(reply(
                    "Не понял профиль. Напишите, например: «моя ниша — английский для IT, аудитория — джуны, тон дружелюбный».",
                    model));
        }
        boolean household = "household".equalsIgnoreCase(text(draft, "scope"));
        SetCreatorProfileInput input = new SetCreatorProfileInput(
                msg.householdId(),
                household ? null : msg.userId(),   // self → the sender; household → the default track
                text(draft, "niche"),
                text(draft, "audience"),
                text(draft, "tone"),
                draft.hasNonNull("platforms") ? draft.get("platforms") : null,
                text(draft, "goals"),
                draft.hasNonNull("guardrails") ? draft.get("guardrails") : null,
                text(draft, "notes"));
        return profiles.set(input)
                .map(saved -> reply(successText(household, saved.niche()), model))
                .onErrorResume(e -> {
                    log.warn("set_creator_profile write failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить профиль создателя. Попробуйте позже.", null));
                });
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "creator-profiler SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private JsonNode parseDraft(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            if (!node.isObject() || node.hasNonNull("error")) {
                return null;
            }
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String successText(boolean household, String niche) {
        StringBuilder sb = new StringBuilder(household
                ? "Обновил общий профиль создателя" : "Обновил ваш профиль создателя");
        if (niche != null && !niche.isBlank()) sb.append(" (ниша: ").append(niche).append(")");
        sb.append(". Поправьте, если что-то не так.");
        return sb.toString();
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
