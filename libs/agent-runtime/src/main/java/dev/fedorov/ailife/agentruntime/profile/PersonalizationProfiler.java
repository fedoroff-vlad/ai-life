package dev.fedorov.ailife.agentruntime.profile;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.profile.ProfileScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * The shared <b>personalization-profiler</b> template (ADR-0005): the one deterministic
 * {@code extract → parse → scope → write} skeleton the five per-domain profilers
 * (briefing/creator/nutrition/travel/stylist) copy-pasted. A domain supplies only its {@link ProfileSpec}
 * (SKILL name, field mapping + post-step, write, reply wording); this template owns the orchestration —
 * the {@code temperature 0} llm-gateway {@code DEFAULT} turn with the SKILL as the system prompt, the
 * lenient JSON parse, the {@link ProfileScope} owner resolution, and the soft-fail to a friendly reply.
 * Sibling of {@code SkillRouter} / {@code PickConfirmActRunner} / {@code BriefResponder} in the runtime.
 *
 * <p>Constructed per-agent (it needs the domain's {@link ProfileSpec} at call time, so
 * {@link #setProfile} takes it as an argument — one profiler bean can serve several specs). The extraction
 * must be faithful, not creative: temperature is pinned to {@code 0}. The scope token is read from the
 * draft's {@code "scope"} field ({@code "self"} / {@code "household"}), the shared SKILL convention.
 */
public final class PersonalizationProfiler {

    private static final Logger log = LoggerFactory.getLogger(PersonalizationProfiler.class);

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final ObjectMapper json;

    public PersonalizationProfiler(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                                   ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.json = json;
    }

    /** Extract the member's stated preferences via {@code spec}'s SKILL and upsert the profile. */
    public <I, S> Mono<IntentResponse> setProfile(ProfileSpec<I, S> spec, NormalizedMessage msg) {
        // temperature=0: preference extraction must be deterministic/faithful, not creative.
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody(spec.skillName())),
                LlmMessage.user(msg.text())), 0.0);
        return llm.chat(request)
                .flatMap(r -> build(spec, msg, r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("{} failed: {}", spec.skillName(), e.toString());
                    return Mono.just(reply(spec.failure(), null));
                });
    }

    private <I, S> Mono<IntentResponse> build(ProfileSpec<I, S> spec, NormalizedMessage msg,
                                              String llmContent, String model) {
        JsonNode draft = parseDraft(llmContent);
        if (draft == null) {
            return Mono.just(reply(spec.unparseable(), model));
        }
        String scope = text(draft, "scope");
        boolean household = ProfileScope.isHousehold(scope);
        UUID ownerId = ProfileScope.ownerId(scope, msg.userId());
        return spec.build(draft, ownerId, msg)
                .flatMap(input -> spec.write(input)
                        .map(saved -> withTrace(reply(spec.success(household, saved), model), spec.trace())))
                .switchIfEmpty(Mono.fromSupplier(() -> reply(spec.unparseable(), model)))
                .onErrorResume(e -> {
                    log.warn("{} write failed: {}", spec.skillName(), e.toString());
                    return Mono.just(reply(spec.failure(), null));
                });
    }

    private String skillBody(String skillName) {
        return skills.byName(skillName)
                .map(Skill::body)
                .orElseThrow(() -> new IllegalStateException(
                        skillName + " SKILL.md not loaded — check skills-classpath"));
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
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /** Attach the domain's success why-trace (#485/G2) when it supplies one; success path only. */
    private static IntentResponse withTrace(IntentResponse reply, String trace) {
        return trace == null ? reply : reply.withTrace(trace);
    }
}
