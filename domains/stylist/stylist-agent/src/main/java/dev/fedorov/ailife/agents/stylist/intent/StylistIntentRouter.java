package dev.fedorov.ailife.agents.stylist.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.stylist.chat.StylistChat;
import dev.fedorov.ailife.agents.stylist.flow.GapAnalyst;
import dev.fedorov.ailife.agents.stylist.flow.StylistAdvisor;
import dev.fedorov.ailife.agents.stylist.flow.WardrobeAuditor;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Routes a <b>text</b> message the orchestrator sent to {@code stylist} into one of the agent's three
 * text-intent flows — audit the wardrobe ({@link WardrobeAuditor#audit}), find the gaps to buy
 * ({@link GapAnalyst#analyse}), or assemble an outfit capsule ({@link StylistAdvisor#advise}) — or a plain
 * chat reply ({@link StylistChat}). The photo-driven flows (catalogue a garment / analyse the person from a
 * self-photo) stay a deterministic pre-check in {@code IntentController} (an image attachment is
 * unambiguously a catalogue/analyse ingest, not a text intent), so they never reach this router.
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, skills-vs-flows
 * Bucket 1 / #475): it supplies the stylist-specific parts — the ordered {@code {skillName → flow}}
 * dispatch map ({@code wardrobe-auditor}/{@code gap-analyst}/{@code capsule-advisor}), the
 * {@link StylistChat} fallback, and the intro/decide framing — and the shared router owns the LLM
 * round-trip, the {@link SkillClassifier} parse, and the soft-fail-to-chat dispatch. Each skill's SKILL.md
 * {@code description} is the routing SSOT, so a paraphrase outside the old {@code AUDIT_CUES}/{@code
 * GAP_CUES}/{@code CAPSULE_CUES} keyword lists routes correctly.
 *
 * <p>{@code wardrobe-cataloguer} and {@code style-analyst} are loaded skills but photo-gated (handled by
 * the attachment pre-check), so leaving them out of the map excludes them from both the advertised route
 * set and dispatch (the router keys its route set off the map). Stylist binds no directly-routable MCP
 * tools.
 */
@Component
public class StylistIntentRouter {

    private static final String WARDROBE_AUDITOR = "wardrobe-auditor";
    private static final String GAP_ANALYST = "gap-analyst";
    private static final String CAPSULE_ADVISOR = "capsule-advisor";

    private final SkillRouter router;

    public StylistIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                               AgentManifest manifest, WardrobeAuditor auditor, GapAnalyst gapAnalyst,
                               StylistAdvisor advisor, StylistChat chat) {
        // wardrobe-cataloguer + style-analyst (photo-gated) are deliberately NOT mapped.
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(WARDROBE_AUDITOR, auditor::audit);
        flows.put(GAP_ANALYST, gapAnalyst::analyse);
        flows.put(CAPSULE_ADVISOR, advisor::advise);
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the stylist agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (audit their wardrobe / find the gaps to buy / "
                        + "assemble an outfit capsule) or just talk?",
                flows, chat::reply);
    }

    public Mono<IntentResponse> route(NormalizedMessage msg) {
        return router.route(msg);
    }

    /** The exact classifier prompt {@link #route} builds — replayed by the routing golden. */
    String buildClassifierPrompt() {
        return router.buildClassifierPrompt();
    }
}
