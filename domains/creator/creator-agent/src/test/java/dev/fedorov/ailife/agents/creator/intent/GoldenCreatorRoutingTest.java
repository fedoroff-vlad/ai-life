package dev.fedorov.ailife.agents.creator.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.creator.chat.CreatorChat;
import dev.fedorov.ailife.agents.creator.flow.ContentStrategist;
import dev.fedorov.ailife.agents.creator.profile.CreatorProfiler;
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
 * Golden test for the cue→classifier migration (#475): exercises {@link CreatorIntentRouter} against a
 * <b>real model</b> (local Ollama via a running llm-gateway), asserting <b>structure, not text</b>. Sibling
 * of {@code GoldenNotesRoutingTest}. Two levels: <b>structure</b> (well-formed routing JSON, no hallucinated
 * skill, and never the hub-only {@code greeting-drafter}) and <b>behaviour</b> (deliberately crisp
 * profile/plan requests reach the right flow). The flows are mocked. Opt-in / gated ({@code GOLDEN_LLM});
 * run with {@code scripts/golden.sh -pl domains/creator/creator-agent -Dtest=GoldenCreatorRoutingTest}.
 */
@GoldenLlmTest
class GoldenCreatorRoutingTest {

    private static final Set<String> ACTIONS = Set.of("skill", "chat");
    private static final Set<String> ROUTABLE = Set.of("creator-profiler", "content-strategist");

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final CreatorProfiler creatorProfiler = mock(CreatorProfiler.class);
    private final ContentStrategist strategist = mock(ContentStrategist.class);
    private final CreatorChat chat = mock(CreatorChat.class);
    private final AgentManifest manifest = new AgentManifest(
            "creator", "creator agent", "0.1.0", 8109, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenCreatorRoutingTest.class.getClassLoader()));
    // greeting-drafter is loaded (as in production) but must never be advertised/dispatched by the router.
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("skills/creator/creator-profiler/SKILL.md"),
            skill("skills/creator/content-strategist/SKILL.md"),
            skill("skills/creator/greeting-drafter/SKILL.md")));
    private final CreatorIntentRouter router = new CreatorIntentRouter(
            llm, skills, new SkillClassifier(json), manifest, creatorProfiler, strategist, chat);

    @Test
    void classifierEmitsWellFormedRoutingJson() {
        String prompt = router.buildClassifierPrompt();
        // greeting-drafter is hub-only → it must not even appear in the advertised prompt.
        assertThat(prompt).doesNotContain("greeting-drafter");
        for (String msg : List.of(
                "моя ниша — английский для айтишников, аудитория джуны",
                "что сейчас в тренде, дай идеи для постов",
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
                assertThat(ROUTABLE).as("hallucinated/hub skill '%s' for «%s»", node.path("name").asString(), msg)
                        .contains(node.path("name").asString());
            }
        }
    }

    @Test
    void routesUnambiguousRequestsToTheRightFlow() {
        stubAll();
        router.route(GoldenLlm.message(UUID.randomUUID(), UUID.randomUUID(), "привет"))
                .block(Duration.ofSeconds(180));   // warm-up (prime the prompt prefix cache)

        assertRoutesTo("моя ниша — английский для айтишников, аудитория джуны", "profiler");
        assertRoutesTo("что сейчас в тренде, дай идеи для постов", "strategist");
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

    private void stubAll() {
        when(creatorProfiler.setProfile(any())).thenReturn(Mono.just(sentinel("profiler")));
        when(strategist.run(any())).thenReturn(Mono.just(sentinel("strategist")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));
    }

    private static IntentResponse sentinel(String tag) {
        return new IntentResponse("creator", tag, null);
    }

    private static Skill skill(String path) {
        return GoldenLlm.skill(GoldenCreatorRoutingTest.class.getClassLoader(), path);
    }
}
