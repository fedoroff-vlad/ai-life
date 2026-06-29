package dev.fedorov.ailife.agents.nutritionist.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.nutritionist.http.DietProfileClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.nutrition.SetDietProfileInput;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

/**
 * Turns a typed message stating dietary goals / restrictions into a stored {@code diet_profile} row.
 * One llm-gateway {@code DEFAULT} turn with the {@code diet-profiler} SKILL as the system prompt
 * extracts the profile JSON → write via {@code mcp-nutrition}'s {@code POST /internal/diet-profile}
 * (NU-d1).
 *
 * <p>Scope: the SKILL emits {@code "self"} (the speaker's own profile → {@code ownerId = userId}) or
 * {@code "household"} (everyone → {@code ownerId = null}, the household-default). Named ad-hoc people
 * (wife / infant) are carried inline in the ration request (NU-g), not stored as separate owner rows
 * — {@code diet_profile.owner_id} references a {@code core.users} account. Any stage failing degrades
 * to a friendly message.
 */
@Component
public class DietProfiler {

    private static final Logger log = LoggerFactory.getLogger(DietProfiler.class);
    private static final String SKILL_NAME = "diet-profiler";

    private final DietProfileClient profiles;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public DietProfiler(DietProfileClient profiles, LlmClient llm, SkillRegistry skills,
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
                    log.warn("diet-profiler failed: {}", e.toString());
                    return Mono.just(reply("Не удалось обновить профиль питания. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> write(NormalizedMessage msg, String llmContent, String model) {
        Draft draft = parseDraft(llmContent);
        if (draft == null) {
            return Mono.just(reply(
                    "Не понял цели питания. Напишите, например: «моя цель 2000 ккал, белок 140 г, без орехов».",
                    model));
        }
        boolean household = "household".equalsIgnoreCase(draft.scope());
        SetDietProfileInput input = new SetDietProfileInput(
                msg.householdId(),
                household ? null : msg.userId(),   // self → the sender; household → the default profile
                draft.goalKcal(),
                draft.goalProteinG(),
                draft.goalFatG(),
                draft.goalCarbsG(),
                draft.restrictions(),
                draft.tastes(),
                draft.notes());
        return profiles.set(input)
                .map(saved -> reply(successText(household, saved.goalKcal()), model))
                .onErrorResume(e -> {
                    log.warn("set_diet_profile write failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить профиль питания. Попробуйте позже.", null));
                });
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "diet-profiler SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private Draft parseDraft(String content) {
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
            return new Draft(
                    text(node, "scope"),
                    intOrNull(node, "goal_kcal"),
                    decimalOrNull(node, "goal_protein_g"),
                    decimalOrNull(node, "goal_fat_g"),
                    decimalOrNull(node, "goal_carbs_g"),
                    node.hasNonNull("restrictions") ? node.get("restrictions") : null,
                    node.hasNonNull("tastes") ? node.get("tastes") : null,
                    text(node, "notes"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isNumber() ? node.get(field).asInt() : null;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isNumber()
                ? node.get(field).decimalValue() : null;
    }

    private static String successText(boolean household, Integer goalKcal) {
        StringBuilder sb = new StringBuilder(household
                ? "Обновил профиль питания семьи" : "Обновил ваш профиль питания");
        if (goalKcal != null) sb.append(" (цель ~").append(goalKcal).append(" ккал/день)");
        sb.append(". Поправьте, если что-то не так.");
        return sb.toString();
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    private record Draft(String scope, Integer goalKcal, BigDecimal goalProteinG, BigDecimal goalFatG,
                         BigDecimal goalCarbsG, JsonNode restrictions, JsonNode tastes, String notes) {
    }
}
