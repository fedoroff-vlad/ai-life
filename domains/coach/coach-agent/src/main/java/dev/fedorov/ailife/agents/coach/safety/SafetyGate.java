package dev.fedorov.ailife.agents.coach.safety;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * The crisis gate that runs <b>before</b> any coaching move and short-circuits it (coach spec §Safety).
 * One cheap FAST-channel turn with the {@code safety-check} SKILL classifies the message
 * ({@code {"crisis": true|false}}, temperature=0). The prompt errs toward {@code true} when uncertain;
 * an un-parseable reply or a gateway error degrades to "not a crisis" — with the gateway down the
 * Reflect synthesis fails to a friendly error anyway, so failing closed here would only mask the outage.
 */
@Component
public class SafetyGate {

    private static final Logger log = LoggerFactory.getLogger(SafetyGate.class);
    private static final String SKILL_NAME = "safety-check";

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final ObjectMapper json;

    public SafetyGate(LlmClient llm, SkillRegistry skills, ObjectMapper json) {
        this.llm = llm;
        this.skills = skills;
        this.json = json;
    }

    /** True when the message signals a crisis and the coaching frame must be dropped. */
    public Mono<Boolean> isCrisis(String text) {
        if (text == null || text.isBlank()) {
            return Mono.just(false);
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.FAST, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(text)), 0.0);
        return llm.chat(request)
                .map(r -> parseCrisis(r.content()))
                .onErrorResume(e -> {
                    log.warn("safety check failed, proceeding as non-crisis: {}", e.toString());
                    return Mono.just(false);
                });
    }

    private boolean parseCrisis(String content) {
        if (content == null) {
            return false;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("safety check returned no JSON object, proceeding as non-crisis");
            return false;
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            return node.path("crisis").asBoolean(false);
        } catch (Exception e) {
            log.warn("safety check JSON un-parseable, proceeding as non-crisis: {}", e.toString());
            return false;
        }
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "safety-check SKILL.md not loaded — check skills-classpath"));
    }
}
