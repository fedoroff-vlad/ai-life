package dev.fedorov.ailife.agents.briefing.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.briefing.chat.BriefingChat;
import dev.fedorov.ailife.agents.briefing.flow.BriefingComposer;
import dev.fedorov.ailife.agents.briefing.profile.BriefingProfiler;
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
 * Golden test for the cue→classifier migration (#475): exercises {@link BriefingIntentRouter} against a
 * <b>real model</b> (local Ollama via a running llm-gateway), asserting <b>structure, not text</b>. The
 * briefing sibling of {@code GoldenNutritionRoutingTest}/{@code GoldenDocsRoutingTest}. Two levels:
 * <ul>
 *   <li><b>structure</b> — the real model, given the real router prompt, returns well-formed routing JSON
 *   (a valid {@code action}, a non-hallucinated skill name) for varied inputs;</li>
 *   <li><b>behaviour</b> — deliberately <b>crisp</b> requests reach the right flow end-to-end through
 *   {@link BriefingIntentRouter#route}. The flows are mocked so the assertion is purely "which flow".</li>
 * </ul>
 * Briefing has two text-routable intent skills ({@code briefing-composer} = produce the digest now,
 * {@code briefing-profiler} = set/change preferences). Opt-in / gated via {@link GoldenLlmTest}
 * ({@code GOLDEN_LLM}); run with
 * {@code scripts/golden.sh -pl domains/briefing/briefing-agent -Dtest=GoldenBriefingRoutingTest}.
 */
@GoldenLlmTest
class GoldenBriefingRoutingTest {

    /** The actions the briefing classifier prompt allows (no MCP tools → only skill / chat). */
    private static final Set<String> ACTIONS = Set.of("skill", "chat");
    private static final Set<String> SKILLS = Set.of("briefing-composer", "briefing-profiler");

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final BriefingComposer composer = mock(BriefingComposer.class);
    private final BriefingProfiler profiler = mock(BriefingProfiler.class);
    private final BriefingChat chat = mock(BriefingChat.class);
    private final AgentManifest manifest = new AgentManifest(
            "briefing", "briefing agent", "0.1.0", 8115, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenBriefingRoutingTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("skills/briefing/briefing-composer/SKILL.md"),
            skill("skills/briefing/briefing-profiler/SKILL.md")));
    private final BriefingIntentRouter router = new BriefingIntentRouter(
            llm, skills, new SkillClassifier(json), manifest, composer, profiler, chat);

    /**
     * STRUCTURE — the real model, given the real router prompt, must return well-formed routing JSON: a
     * JSON object with an {@code action} in the contract set and, when {@code action=skill}, a real
     * briefing skill name (never a hallucinated one). "Structure, not text".
     */
    @Test
    void classifierEmitsWellFormedRoutingJson() {
        String prompt = router.buildClassifierPrompt();
        for (String msg : List.of(
                "собери мне брифинг на сегодня",
                "настрой утренний брифинг: погода в Москве, новости про ИИ, в 8 утра",
                "brief me now",
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
     * {@link BriefingIntentRouter#route}. A softer signal than the structure test (a small model can
     * mis-route a borderline phrasing), so the cases here are deliberately crisp.
     */
    @Test
    void routesUnambiguousRequestsToTheRightFlow() {
        stubAll();
        // Warm-up (not asserted): the classifier system prompt is large, so its FIRST prefill on a CPU-only
        // box can exceed the per-call block below. Ollama caches the prompt prefix, so this primes it.
        router.route(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(), "привет"))
                .block(Duration.ofSeconds(180));

        assertRoutesTo("собери мне брифинг на сегодня", "composer");
        assertRoutesTo("спасибо, очень помог!", "chat");
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
        when(composer.digest(any())).thenReturn(Mono.just(sentinel("composer")));
        when(profiler.setProfile(any())).thenReturn(Mono.just(sentinel("profiler")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));
    }

    private static IntentResponse sentinel(String tag) {
        return new IntentResponse("briefing", tag, null);
    }

    private static Skill skill(String path) {
        return GoldenLlm.skill(GoldenBriefingRoutingTest.class.getClassLoader(), path);
    }
}
