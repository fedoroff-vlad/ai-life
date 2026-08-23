package dev.fedorov.ailife.agents.notes.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.chat.NotesChat;
import dev.fedorov.ailife.agents.notes.find.NoteFinder;
import dev.fedorov.ailife.agents.notes.list.ListManager;
import dev.fedorov.ailife.agents.notes.write.NoteWriter;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden test for the cue→classifier migration (#475): exercises {@link NotesIntentRouter} against a
 * <b>real model</b> (local Ollama via a running llm-gateway), asserting <b>structure, not text</b>. The
 * notes sibling of tasks/finance {@code GoldenRoutingTest}. Two levels, matching that reference:
 * <ul>
 *   <li><b>structure</b> — the real model, given the real router prompt, returns well-formed routing JSON
 *   (a valid {@code action}, a non-hallucinated skill name) for varied inputs;</li>
 *   <li><b>behaviour</b> — deliberately <b>crisp</b> requests reach the right flow end-to-end through
 *   {@link NotesIntentRouter#route} (a small local model can mis-route a borderline paraphrase, so — like
 *   the reference — the asserted cases are unambiguous). The four flows are mocked so the assertion is
 *   purely "which flow did the router pick".</li>
 * </ul>
 * Opt-in / gated via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); run with
 * {@code scripts/golden.sh -pl domains/knowledge/notes-agent -Dtest=GoldenNotesRoutingTest}.
 */
@GoldenLlmTest
class GoldenNotesRoutingTest {

    /** The actions the notes classifier prompt allows (no MCP tools → only skill / chat). */
    private static final Set<String> ACTIONS = Set.of("skill", "chat");
    private static final Set<String> SKILLS = Set.of("note-writer", "note-finder", "list-manager", "note-delete");

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final NoteWriter writer = mock(NoteWriter.class);
    private final NoteFinder finder = mock(NoteFinder.class);
    private final ListManager lists = mock(ListManager.class);
    private final NoteDeleter deleter = mock(NoteDeleter.class);
    private final NotesChat chat = mock(NotesChat.class);
    private final AgentManifest manifest = new AgentManifest(
            "notes", "notes agent", "0.1.0", 8118, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenNotesRoutingTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("skills/knowledge/note-writer/SKILL.md"),
            skill("skills/knowledge/note-finder/SKILL.md"),
            skill("skills/knowledge/list-manager/SKILL.md"),
            skill("skills/knowledge/note-delete/SKILL.md")));
    private final NotesIntentRouter router = new NotesIntentRouter(
            llm, skills, new SkillClassifier(json), manifest, writer, finder, lists, deleter, chat);

    /**
     * STRUCTURE — the real model, given the real router prompt, must return well-formed routing JSON: a
     * JSON object with an {@code action} in the contract set and, when {@code action=skill}, a real skill
     * name (never a hallucinated one). "Structure, not text" — never checks wording.
     */
    @Test
    void classifierEmitsWellFormedRoutingJson() {
        String prompt = router.buildClassifierPrompt();
        for (String msg : List.of(
                "что я думал про подарок маме?",
                "добавь молоко в список покупок",
                "запомни, что мама любит пионы в горшке",
                "удали заметку про отпуск",
                "привет, как дела?")) {
            String raw = chat(prompt, msg);
            JsonNode node = extractJson(raw);
            if (node == null) {
                fail("Not parseable JSON for «%s» — raw model output was:\n%s".formatted(msg, raw));
            }
            assertThat(node.hasNonNull("action")).as("missing 'action' for «%s»: %s", msg, raw).isTrue();
            String action = node.get("action").asString();
            assertThat(ACTIONS).as("action '%s' not in the contract for «%s»: %s", action, msg, raw)
                    .contains(action);
            if ("skill".equals(action)) {
                assertThat(SKILLS).as("hallucinated skill '%s' for «%s»", node.path("name").asString(), msg)
                        .contains(node.path("name").asString());
            }
        }
    }

    /**
     * BEHAVIOUR — unambiguous requests must reach the right flow end-to-end through
     * {@link NotesIntentRouter#route}. A softer signal than the structure test (a small model can mis-route
     * a borderline phrasing), so the cases here are deliberately crisp.
     */
    @Test
    void routesUnambiguousRequestsToTheRightFlow() {
        stubAll();
        // Warm-up (not asserted): the classifier system prompt is large, so its FIRST prefill on a CPU-only
        // box can exceed the per-call block below. Ollama caches the prompt prefix, so this primes it.
        router.route(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(), "привет"))
                .block(Duration.ofSeconds(180));

        assertRoutesTo("что я думал про подарок маме?", "finder");
        assertRoutesTo("добавь молоко в список покупок", "list");
        assertRoutesTo("запомни, что мама любит пионы в горшке", "writer");
        assertRoutesTo("удали заметку про отпуск", "deleter");
    }

    private void assertRoutesTo(String text, String expectedFlow) {
        IntentResponse resp = router.route(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(), text))
                .block(Duration.ofSeconds(90));
        assertThat(resp).as("null result for «%s» — is llm-gateway up at %s?", text, GoldenLlm.gatewayUrl())
                .isNotNull();
        assertThat(resp.text())
                .as("«%s» should route to the '%s' flow but got '%s'", text, expectedFlow, resp.text())
                .isEqualTo(expectedFlow);
    }

    /** One real round-trip through the live model with the exact router prompt shape (structure test). */
    private String chat(String classifierPrompt, String userText) {
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(classifierPrompt),
                LlmMessage.user(userText)));
        LlmChatResponse resp = llm.chat(req).block(Duration.ofSeconds(120));
        assertThat(resp).as("no LLM response for «%s» — is llm-gateway up at %s?", userText, GoldenLlm.gatewayUrl())
                .isNotNull();
        return resp.content() == null ? "" : resp.content();
    }

    /** Lenient extraction: tolerate ```json fences / leading prose or <think> blocks; parse the first object. */
    private JsonNode extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode n = json.readTree(raw.substring(start, end + 1));
            return n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Stub every flow with a distinct sentinel so the returned text names the flow the router picked. */
    private void stubAll() {
        when(finder.find(any())).thenReturn(Mono.just(sentinel("finder")));
        when(lists.handle(any())).thenReturn(Mono.just(sentinel("list")));
        when(writer.capture(any())).thenReturn(Mono.just(sentinel("writer")));
        when(deleter.delete(any())).thenReturn(Mono.just(sentinel("deleter")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));
    }

    private static IntentResponse sentinel(String tag) {
        return new IntentResponse("notes", tag, null);
    }

    private static Skill skill(String path) {
        return GoldenLlm.skill(GoldenNotesRoutingTest.class.getClassLoader(), path);
    }
}
