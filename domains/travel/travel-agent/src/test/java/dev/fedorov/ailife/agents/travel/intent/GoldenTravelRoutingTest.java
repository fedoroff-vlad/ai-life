package dev.fedorov.ailife.agents.travel.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.travel.chat.TravelChat;
import dev.fedorov.ailife.agents.travel.flow.PackingFlow;
import dev.fedorov.ailife.agents.travel.flow.RouteFlow;
import dev.fedorov.ailife.agents.travel.flow.TripComposer;
import dev.fedorov.ailife.agents.travel.flow.WalletFlow;
import dev.fedorov.ailife.agents.travel.profile.TravelProfiler;
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
 * Golden test for the cue→classifier migration (#475): exercises {@link TravelIntentRouter} against a
 * <b>real model</b> (local Ollama via a running llm-gateway), asserting <b>structure, not text</b>. The
 * travel sibling of {@code GoldenNutritionRoutingTest}/{@code GoldenStylistRoutingTest}, and the last of the
 * eight cue-routed agents. Two levels:
 * <ul>
 *   <li><b>structure</b> — the real model, given the real router prompt, returns well-formed routing JSON
 *   (a valid {@code action}, a non-hallucinated skill name) for varied inputs — including the packing
 *   paraphrase ("а что кинуть в чемодан") that motivated #475;</li>
 *   <li><b>behaviour</b> — deliberately <b>crisp</b> requests reach the right flow end-to-end through
 *   {@link TravelIntentRouter#route}. The flows are mocked so the assertion is purely "which flow".</li>
 * </ul>
 * Travel has four text-routable intent skills; the route-file attachment import is a controller pre-check
 * (not routed). Opt-in / gated via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); run with
 * {@code scripts/golden.sh -pl domains/travel/travel-agent -Dtest=GoldenTravelRoutingTest}.
 */
@GoldenLlmTest
class GoldenTravelRoutingTest {

    /** The actions the travel classifier prompt allows (no MCP tools → only skill / chat). */
    private static final Set<String> ACTIONS = Set.of("skill", "chat");
    private static final Set<String> SKILLS = Set.of(
            "travel-profiler", "trip-wallet", "packing-list", "trip-composer");

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final TravelProfiler profiler = mock(TravelProfiler.class);
    private final WalletFlow wallet = mock(WalletFlow.class);
    private final PackingFlow packing = mock(PackingFlow.class);
    private final TripComposer composer = mock(TripComposer.class);
    private final RouteFlow route = mock(RouteFlow.class);
    private final TravelChat chat = mock(TravelChat.class);
    private final AgentManifest manifest = new AgentManifest(
            "travel", "travel agent", "0.1.0", 8117, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenTravelRoutingTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("skills/travel/travel-profiler/SKILL.md"),
            skill("skills/travel/trip-wallet/SKILL.md"),
            skill("skills/travel/packing-list/SKILL.md"),
            skill("skills/travel/trip-composer/SKILL.md")));
    private final TravelIntentRouter router = new TravelIntentRouter(
            llm, skills, new SkillClassifier(json), manifest, profiler, wallet, packing, composer, route, chat);

    /**
     * STRUCTURE — the real model, given the real router prompt, must return well-formed routing JSON: a
     * JSON object with an {@code action} in the contract set and, when {@code action=skill}, a real travel
     * skill name (never a hallucinated one). "Structure, not text".
     */
    @Test
    void classifierEmitsWellFormedRoutingJson() {
        String prompt = router.buildClassifierPrompt();
        for (String msg : List.of(
                "летаем из Москвы, любим спокойный пляжный отдых семьёй",
                "потратил 2000 бат на ужин",
                "а что кинуть в чемодан?",
                "хочу на море в сентябре, бюджет тысяч 200",
                "спасибо, отличные идеи!")) {
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
     * {@link TravelIntentRouter#route}. A softer signal than the structure test (a small model can mis-route
     * a borderline phrasing), so the cases here are deliberately crisp.
     */
    @Test
    void routesUnambiguousRequestsToTheRightFlow() {
        stubAll();
        // Warm-up (not asserted): the classifier system prompt is large, so its FIRST prefill on a CPU-only
        // box can exceed the per-call block below. Ollama caches the prompt prefix, so this primes it.
        router.route(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(), "привет"))
                .block(Duration.ofSeconds(180));

        assertRoutesTo("что взять с собой в поездку?", "packing");
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
        when(profiler.setProfile(any())).thenReturn(Mono.just(sentinel("profiler")));
        when(wallet.handle(any())).thenReturn(Mono.just(sentinel("wallet")));
        when(packing.handle(any())).thenReturn(Mono.just(sentinel("packing")));
        when(composer.plan(any())).thenReturn(Mono.just(sentinel("composer")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));
    }

    private static IntentResponse sentinel(String tag) {
        return new IntentResponse("travel", tag, null);
    }

    private static Skill skill(String path) {
        return GoldenLlm.skill(GoldenTravelRoutingTest.class.getClassLoader(), path);
    }
}
