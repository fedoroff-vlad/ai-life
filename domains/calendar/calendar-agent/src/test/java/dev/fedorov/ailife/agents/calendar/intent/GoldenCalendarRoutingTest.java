package dev.fedorov.ailife.agents.calendar.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.calendar.chat.CalendarChat;
import dev.fedorov.ailife.agents.calendar.flow.EventCanceller;
import dev.fedorov.ailife.agents.calendar.flow.EventCapturer;
import dev.fedorov.ailife.agents.calendar.flow.EventMover;
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
 * Routing golden for {@link CalendarIntentRouter} (#544, closing the gap that calendar was the only
 * cue-routed agent with no {@code Golden*RoutingTest}). The calendar sibling of tasks/finance/notes
 * {@code Golden*RoutingTest} — exercises the router against a <b>real model</b> (local Ollama via a
 * running llm-gateway), asserting <b>structure, not text</b>. Two levels, matching the reference:
 * <ul>
 *   <li><b>structure</b> — the real model, given the real router prompt, returns well-formed routing JSON
 *   (a valid {@code action}, a non-hallucinated skill name) for varied inputs;</li>
 *   <li><b>behaviour</b> — deliberately <b>crisp</b> chat-CRUD requests reach the right flow end-to-end
 *   through {@link CalendarIntentRouter#route} (a small local model can mis-route a borderline paraphrase,
 *   so — like the reference — the asserted cases are unambiguous). The flows are mocked so the assertion
 *   is purely "which flow did the router pick". The wake-driven birthday/gift skills are off the router
 *   map, so they are neither loaded nor advertised here — mirroring production.</li>
 * </ul>
 * Opt-in / gated via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); run with
 * {@code scripts/golden.sh -pl domains/calendar/calendar-agent -Dtest=GoldenCalendarRoutingTest}.
 * Cost: one CPU-only Ollama lane (~2–3 min on the dev box; the behaviour test primes the prompt prefix).
 */
@GoldenLlmTest
class GoldenCalendarRoutingTest {

    /** The actions the calendar classifier prompt allows (no MCP tools → only skill / chat). */
    private static final Set<String> ACTIONS = Set.of("skill", "chat");
    private static final Set<String> SKILLS = Set.of("event-capture", "event-cancel", "event-move");

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final EventCapturer capturer = mock(EventCapturer.class);
    private final EventCanceller canceller = mock(EventCanceller.class);
    private final EventMover mover = mock(EventMover.class);
    private final CalendarChat chat = mock(CalendarChat.class);
    private final AgentManifest manifest = new AgentManifest(
            "calendar", "calendar agent", "0.1.0", 8086, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenCalendarRoutingTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("skills/calendar/event-capture/SKILL.md"),
            skill("skills/calendar/event-cancel/SKILL.md"),
            skill("skills/calendar/event-move/SKILL.md")));
    private final CalendarIntentRouter router = new CalendarIntentRouter(
            llm, skills, new SkillClassifier(json), manifest, capturer, canceller, mover, chat);

    /**
     * STRUCTURE — the real model, given the real router prompt, must return well-formed routing JSON: a
     * JSON object with an {@code action} in the contract set and, when {@code action=skill}, a real skill
     * name (never a hallucinated one). "Structure, not text" — never checks wording.
     */
    @Test
    void classifierEmitsWellFormedRoutingJson() {
        String prompt = router.buildClassifierPrompt();
        for (String msg : List.of(
                "запиши встречу с врачом завтра в 15",
                "отмени встречу с врачом",
                "перенеси встречу с врачом на 16:00",
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
     * BEHAVIOUR — unambiguous chat-CRUD requests must reach the right flow end-to-end through
     * {@link CalendarIntentRouter#route}. A softer signal than the structure test (a small model can
     * mis-route a borderline phrasing), so the cases here are deliberately crisp.
     */
    @Test
    void routesUnambiguousRequestsToTheRightFlow() {
        stubAll();
        // Warm-up (not asserted): the classifier system prompt is large, so its FIRST prefill on a CPU-only
        // box can exceed the per-call block below. Ollama caches the prompt prefix, so this primes it.
        router.route(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(), "привет"))
                .block(Duration.ofSeconds(180));

        assertRoutesTo("запиши встречу с врачом завтра в 15", "capture");
        assertRoutesTo("отмени встречу с врачом", "cancel");
        assertRoutesTo("перенеси встречу с врачом на 16:00", "move");
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
        when(capturer.capture(any())).thenReturn(Mono.just(sentinel("capture")));
        when(canceller.cancel(any())).thenReturn(Mono.just(sentinel("cancel")));
        when(mover.move(any())).thenReturn(Mono.just(sentinel("move")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));
    }

    private static IntentResponse sentinel(String tag) {
        return new IntentResponse("calendar", tag, null);
    }

    private static Skill skill(String path) {
        return GoldenLlm.skill(GoldenCalendarRoutingTest.class.getClassLoader(), path);
    }
}
