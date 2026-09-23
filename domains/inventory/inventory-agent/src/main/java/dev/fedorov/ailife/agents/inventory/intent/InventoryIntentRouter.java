package dev.fedorov.ailife.agents.inventory.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.inventory.chat.InventoryChat;
import dev.fedorov.ailife.agents.inventory.pack.BoxPacker;
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
 * Routes a <b>text</b> message the orchestrator sent to {@code inventory} into the packing session
 * ({@link BoxPacker}) or a plain chat reply ({@link InventoryChat}).
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, skills-vs-flows
 * Bucket 1 / #475): it supplies the inventory-specific parts — the {@code {skillName → flow}} dispatch
 * map ({@code box-packer}), the chat fallback and the intro/decide framing — while the shared router
 * owns the LLM round-trip, the {@link SkillClassifier} parse and the soft-fail-to-chat dispatch. The
 * {@code box-packer} SKILL.md {@code description} is the routing SSOT.
 *
 * <p>Photos never reach this router: while a session is open they arrive on {@code /resume} (the
 * conversation is route-locked), and outside one they are a deterministic pre-check in
 * {@code IntentController} — a photo is unambiguous, so asking an LLM to classify it would only add
 * latency and a failure mode.
 */
@Component
public class InventoryIntentRouter {

    private static final String BOX_PACKER = "box-packer";

    private final SkillRouter router;

    public InventoryIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                                 AgentManifest manifest, BoxPacker packer, InventoryChat chat) {
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(BOX_PACKER, packer::start);
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the inventory agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (start or finish packing a storage container) "
                        + "or just talk?",
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
