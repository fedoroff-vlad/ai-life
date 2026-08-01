package dev.fedorov.ailife.agents.finance.advisor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.read.SpendingReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reactive on-request spending <b>analysis</b> — the finance MVP's third leg
 * (after receipt-capture + confirm). When the user asks "проанализируй мои траты" /
 * "дай сводку", the {@link dev.fedorov.ailife.agents.finance.intent.IntentRouter}
 * classifier routes here.
 *
 * <p>Built on the shared {@link Coordinator} substrate (gather → synthesize), like
 * calendar-agent's gift-recommender. It gathers, in parallel, two spend-by-category
 * windows — <b>recent</b> (last 90 days) and <b>previous</b> (the 90 days before that) —
 * from mcp-finance's {@code /internal/spending-by-category} passthrough, folds them into
 * a {@code context}, and asks the LLM to synthesize a concise analysis from
 * {@code [AGENT.md, financial-advisor SKILL.md] + {payload(userText), context}}: top
 * categories, what changed vs the prior window and a hypothesis why, plus a couple of
 * optimisation hints. Text-first — chart rendering is a deferred shared capability
 * (see finance.md "recorded vision"). Each gather step soft-fails independently
 * (Coordinator), and the whole flow degrades to a friendly message on error.
 *
 * <p><b>Sharing scope (ADR-0002 slice 4a — the shared personal/shared read capability).</b> By default
 * the analysis is the member's <b>own</b> spending — the envelope {@code householdId} is their personal
 * household ({@code IdentityResolver} creates it on first contact). When the user explicitly asks about
 * <b>shared/family</b> spending ("наши траты"), the classifier sets {@code scope:"shared"} and this flow
 * reads the union across the member's household set (personal ∪ shared) through the shared
 * {@link SpendingReads} helper — the one place the personal-vs-shared cut lives for every finance read
 * flow, so it is not copy-pasted here, in the reporters, and anywhere else. An empty/failed set degrades
 * to the envelope household, so a profile hiccup never breaks the analysis.
 */
@Component
public class FinancialAdvisor {

    private static final Logger log = LoggerFactory.getLogger(FinancialAdvisor.class);
    private static final String SKILL_NAME = "financial-advisor";
    private static final Duration WINDOW = Duration.ofDays(90);

    private final Coordinator coordinator;
    private final SpendingReads reads;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public FinancialAdvisor(Coordinator coordinator,
                            SpendingReads reads,
                            SkillRegistry skills,
                            AgentManifest manifest,
                            ObjectMapper json) {
        this.coordinator = coordinator;
        this.reads = reads;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    /** Analyse the member's own (personal-household) spending. */
    public Mono<AdviceResult> advise(NormalizedMessage msg) {
        return advise(msg, false);
    }

    /**
     * @param shared when {@code true}, union the read across the member's personal ∪ shared households
     *               (the "наши траты" cut); otherwise analyse only the envelope (personal) household.
     */
    public Mono<AdviceResult> advise(NormalizedMessage msg, boolean shared) {
        UUID household = msg == null ? null : msg.householdId();
        if (household == null) {
            return Mono.just(new AdviceResult(
                    "Не вижу, чей это бюджет — не могу проанализировать траты.", null));
        }
        UUID userId = msg.userId();
        return reads.households(household, userId, shared).flatMap(households -> {
            Instant now = Instant.now();
            Instant recentFrom = now.minus(WINDOW);
            Instant prevFrom = recentFrom.minus(WINDOW);

            Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
            gather.put("recent", spendingNode(households, recentFrom, now));
            gather.put("previous", spendingNode(households, prevFrom, recentFrom));

            ObjectNode payload = json.createObjectNode();
            if (msg.text() != null && !msg.text().isBlank()) {
                payload.put("userText", msg.text());
            }
            payload.put("recentWindowDays", WINDOW.toDays());
            payload.put("comparedToPriorWindow", true);
            // Tell the synthesis which cut it is analysing so it frames the reply ("твои"/"семейные").
            payload.put("scope", shared ? "shared" : "personal");

            return coordinator.coordinate(
                            List.of(manifest.body(), skillBody()),
                            payload,
                            gather,
                            LlmChannel.DEFAULT)
                    .map(r -> new AdviceResult(r.text(), r.llmModel()));
        }).onErrorResume(e -> {
            log.warn("financial-advisor failed for household {}: {}", household, e.toString());
            return Mono.just(new AdviceResult(
                    "Не смог собрать данные для анализа трат. Попробуйте позже.", null));
        });
    }

    /**
     * One spend-by-category window as JSON, unioned across {@code households} via the shared
     * {@link SpendingReads} (a single-element list on the personal cut → identical to the pre-sharing
     * behaviour). An empty window is omitted by the Coordinator.
     */
    private Mono<JsonNode> spendingNode(List<UUID> households, Instant from, Instant to) {
        return reads.spendingUnion(households, from, to)
                .map(rows -> (JsonNode) json.valueToTree(rows));
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "financial-advisor SKILL.md not loaded — check skills-classpath"));
    }

    /** The synthesized analysis text plus the model that produced it (for the response contract). */
    public record AdviceResult(String text, String model) {
    }
}
