package dev.fedorov.ailife.agents.docs.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.docs.chat.DocsChat;
import dev.fedorov.ailife.agents.docs.find.DocFinder;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Routes a <b>text</b> message the orchestrator sent to {@code docs} into the "find my X" flow
 * ({@link DocFinder}) or a plain chat reply ({@link DocsChat}). The photo-ingest branch stays a
 * deterministic pre-check in {@code IntentController} (an image attachment is unambiguously an ingest,
 * not a text intent), so it never reaches this router.
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, skills-vs-flows
 * Bucket 1 / #475): it supplies the docs-specific parts — the ordered {@code {skillName → flow}} dispatch
 * map (`doc-finder`), the {@link DocsChat} fallback, and the intro/decide framing — and the shared router
 * owns the LLM round-trip, the {@link SkillClassifier} parse, and the soft-fail-to-chat dispatch. The
 * {@code doc-finder} SKILL.md {@code description} is the routing SSOT, so a paraphrase outside the old
 * {@code FIND_CUES} keyword list ("нужен договор за прошлый год") routes correctly.
 *
 * <p>Only {@code doc-finder} is in the dispatch map. {@code doc-archiver} is a loaded skill with empty
 * {@code triggers} but is <b>attachment-gated</b> (only a photo message triggers an ingest, handled in
 * {@code IntentController} before this router), not a text intent, so leaving it out of the map excludes
 * it from both the advertised route set and dispatch — the router keys its route set off the map. Docs
 * binds no directly-routable MCP tools.
 *
 * <p>The <b>family/own scope</b> for the find flow stays a deterministic keyword heuristic
 * ({@code FAMILY_CUES}) applied <i>inside</i> the dispatch lambda — it is a read-breadth choice
 * (personal ∪ shared households, ADR-0002 slice 7b), never a routing or privacy-write decision, so it is
 * deliberately not handed to the LLM classifier.
 */
@Component
public class DocsIntentRouter {

    private static final String DOC_FINDER = "doc-finder";

    /**
     * Widen the search to the member's personal ∪ shared households (ADR-0002 slice 7b). Default is the
     * member's own archive; a family cue asks for "наши/семейные документы". Deterministic keyword match,
     * a read-breadth choice, never a privacy write boundary (kept off the LLM classifier by design).
     */
    private static final Set<String> FAMILY_CUES = Set.of(
            "наши документы", "наш документ", "семейные документы", "семейный документ", "у нас есть",
            "у нас дома", "домашние документы", "документы семьи", "на всю семью", "для семьи",
            "our documents", "our document", "family documents", "family document", "household documents",
            "do we have", "shared documents");

    private final SkillRouter router;

    public DocsIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                            AgentManifest manifest, DocFinder finder, DocsChat chat) {
        // doc-archiver is attachment-gated (handled in IntentController) → deliberately NOT in the map.
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(DOC_FINDER, msg -> finder.find(msg, isFamilyCut(msg.text())));
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the docs agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (find a document in their archive) or just talk?",
                flows, chat::reply);
    }

    public Mono<IntentResponse> route(NormalizedMessage msg) {
        return router.route(msg);
    }

    private static boolean isFamilyCut(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return FAMILY_CUES.stream().anyMatch(t::contains);
    }

    /** The exact classifier prompt {@link #route} builds — replayed by the routing golden. */
    String buildClassifierPrompt() {
        return router.buildClassifierPrompt();
    }
}
