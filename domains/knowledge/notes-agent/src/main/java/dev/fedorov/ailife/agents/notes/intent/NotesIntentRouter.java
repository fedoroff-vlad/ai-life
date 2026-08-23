package dev.fedorov.ailife.agents.notes.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.chat.NotesChat;
import dev.fedorov.ailife.agents.notes.find.NoteFinder;
import dev.fedorov.ailife.agents.notes.list.ListManager;
import dev.fedorov.ailife.agents.notes.write.NoteWriter;
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
 * Routes a message the orchestrator sent to {@code notes} into one of the agent's flows — recall
 * ({@link NoteFinder}), list op ({@link ListManager}, LI-a), capture ({@link NoteWriter}) — or a plain
 * chat reply ({@link NotesChat}).
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, skills-vs-flows
 * Bucket 1 / #475): it supplies the notes-specific parts — the ordered {@code {skillName → flow}} dispatch
 * map (`note-finder`/`list-manager`/`note-writer`), the {@link NotesChat} fallback, and the intro/decide
 * framing — and the shared router owns the LLM round-trip, the {@link SkillClassifier} parse, and the
 * soft-fail-to-chat dispatch. Their SKILL.md {@code description}s are the routing SSOT, so a paraphrase
 * outside the old cue list ("а что я записывал по кухне") routes correctly. notes-agent binds no MCP tools.
 */
@Component
public class NotesIntentRouter {

    // The intent-skill names (SKILL.md frontmatter `name`, the routing SSOT) → their flow objects.
    private static final String NOTE_FINDER = "note-finder";
    private static final String LIST_MANAGER = "list-manager";
    private static final String NOTE_WRITER = "note-writer";
    private static final String NOTE_DELETE = "note-delete";

    private final SkillRouter router;

    public NotesIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                             AgentManifest manifest, NoteWriter writer, NoteFinder finder,
                             ListManager lists, NoteDeleter deleter, NotesChat chat) {
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(NOTE_FINDER, finder::find);
        flows.put(LIST_MANAGER, lists::handle);
        flows.put(NOTE_WRITER, writer::capture);
        flows.put(NOTE_DELETE, deleter::delete);
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the notes agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (recall a note / manage a list / capture a note / "
                        + "delete a note) or just talk?",
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
