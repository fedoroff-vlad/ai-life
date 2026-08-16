package dev.fedorov.ailife.agents.notes.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.Choice;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.chat.NotesChat;
import dev.fedorov.ailife.agents.notes.find.NoteFinder;
import dev.fedorov.ailife.agents.notes.list.ListManager;
import dev.fedorov.ailife.agents.notes.write.NoteWriter;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
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
 * Routes a message the orchestrator sent to {@code notes} into one of the agent's flows — recall
 * ({@link NoteFinder}), list op ({@link ListManager}, LI-a), capture ({@link NoteWriter}) — or a plain
 * chat reply ({@link NotesChat}).
 *
 * <p>Replaces the previous hardcoded keyword-cue heuristic in {@code IntentController} with the shared
 * {@link SkillClassifier} ({@code libs/agent-runtime}, skills-vs-flows Bucket 1 — the same LLM-classified
 * path finance/tasks use). The agent's three intent skills (`note-writer`/`note-finder`/`list-manager`)
 * are offered to the model as one {@code skill} {@link Choice}; their SKILL.md {@code description}s are the
 * trigger SSOT, so a paraphrase outside the old cue list ("а что я записывал по кухне") routes correctly.
 * notes-agent binds no MCP tools, so the classifier chooses between {@code skill} and {@code chat} only.
 *
 * <p>Every stage soft-fails to the {@link NotesChat} fallback: a blank message, no intent skill loaded,
 * an unknown skill name, non-routing prose, or an LLM error — none throw, all reply conversationally
 * (preserving the pre-migration fallback UX).
 */
@Component
public class NotesIntentRouter {

    private static final Logger log = LoggerFactory.getLogger(NotesIntentRouter.class);

    /** The single classifier action the intent skills are offered under (one action, many skills). */
    private static final String SKILL_ACTION = "skill";
    // The intent-skill names (SKILL.md frontmatter `name`, the routing SSOT) → their flow objects.
    private static final String NOTE_FINDER = "note-finder";
    private static final String NOTE_WRITER = "note-writer";
    private static final String LIST_MANAGER = "list-manager";

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final SkillClassifier classifier;
    private final AgentManifest manifest;
    private final NoteWriter writer;
    private final NoteFinder finder;
    private final ListManager lists;
    private final NotesChat chat;

    public NotesIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                             AgentManifest manifest, NoteWriter writer, NoteFinder finder,
                             ListManager lists, NotesChat chat) {
        this.llm = llm;
        this.skills = skills;
        this.classifier = classifier;
        this.manifest = manifest;
        this.writer = writer;
        this.finder = finder;
        this.lists = lists;
        this.chat = chat;
    }

    public Mono<IntentResponse> route(NormalizedMessage msg) {
        String text = msg == null ? null : msg.text();
        if (text == null || text.isBlank()) {
            return chat.reply(msg);                       // the empty-text hint
        }
        List<Skill> intentSkills = intentSkills();
        if (intentSkills.isEmpty()) {
            return chat.reply(msg);                       // nothing to route → plain chat (skeleton back-compat)
        }
        List<Choice> choices = choices(intentSkills);
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(buildClassifierPrompt(intentSkills)),
                LlmMessage.user(text)));
        return llm.chat(req)
                .flatMap(resp -> {
                    String raw = resp.content() == null ? "" : resp.content().trim();
                    return switch (classifier.parse(raw, List.of(), choices)) {
                        case SkillClassifier.FlowCall f -> dispatch(f, msg);
                        case SkillClassifier.Chat c -> chat.reply(msg);
                        // notes binds no MCP tools; a tool call can't be produced, but stay total.
                        case SkillClassifier.ToolCall t -> chat.reply(msg);
                    };
                })
                .onErrorResume(e -> {
                    log.warn("notes routing failed: {}", e.toString());
                    return chat.reply(msg);
                });
    }

    /** Run the flow the LLM named in {@code node.name}; an unknown name soft-falls to chat. */
    private Mono<IntentResponse> dispatch(SkillClassifier.FlowCall f, NormalizedMessage msg) {
        String name = f.node().path("name").asString(null);
        if (name == null) {
            return chat.reply(msg);
        }
        return switch (name) {
            case NOTE_FINDER -> finder.find(msg);
            case LIST_MANAGER -> lists.handle(msg);
            case NOTE_WRITER -> writer.capture(msg);
            default -> {
                log.warn("LLM routed notes to unknown skill '{}' — chat fallback", name);
                yield chat.reply(msg);
            }
        };
    }

    /** Skills with no {@code triggers} are user-invoked (a trigger skill is scheduler-fired, not routed). */
    private List<Skill> intentSkills() {
        return skills.all().stream()
                .filter(s -> s.triggers() == null || s.triggers().isEmpty())
                .toList();
    }

    /** The intent skills collapsed into one {@code skill} choice (the LLM names the skill in {@code node.name}). */
    private List<Choice> choices(List<Skill> intentSkills) {
        StringBuilder block = new StringBuilder(
                "Available skills (multi-step flows — pick one only when the user clearly asks for it):");
        for (Skill s : intentSkills) {
            block.append("\n- ").append(s.name()).append(": ").append(s.description());
        }
        return List.of(new Choice(SKILL_ACTION, block.toString(),
                "{\"action\":\"skill\",\"name\":\"<skill-name>\"}"));
    }

    /** The exact classifier prompt {@link #route} builds — replayed by the routing golden. */
    String buildClassifierPrompt() {
        return buildClassifierPrompt(intentSkills());
    }

    /**
     * Build the classifier system prompt via the shared {@link SkillClassifier#buildPrompt} scaffold.
     * Package-private so a {@code @GoldenLlmTest} can replay the exact prompt against a live model.
     */
    String buildClassifierPrompt(List<Skill> intentSkills) {
        return classifier.buildPrompt(
                "You are routing a message for the notes agent. Reply directly to the user, or run one skill.",
                List.of(),                                // no MCP tools
                choices(intentSkills),
                "Decide: does the user want to run a skill (recall a note / manage a list / capture a note) "
                        + "or just talk?");
    }
}
