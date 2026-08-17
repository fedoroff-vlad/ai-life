package dev.fedorov.ailife.agentruntime.intent;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the shared {@link SkillRouter} (skills-vs-flows Bucket 1 / #475): a mock
 * {@link LlmClient} drives each classifier branch and the flows are captured, so we assert exactly which
 * flow the router dispatched to. Covers the parity behaviours the per-agent {@code notes}/{@code creator}
 * routers used to test locally, plus the map-keyed route set (a loaded skill absent from the dispatch map
 * — the hub-invoked exclusion case — is neither advertised nor dispatched).
 */
class SkillRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final AgentManifest manifest = new AgentManifest(
            "demo", "test", "0.0.1", 0, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the demo agent for the ai-life system.");
    // 'ghost-hub' is loaded but deliberately NOT in the dispatch map — the hub-invoked exclusion case.
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("alpha", "Run the alpha flow when the user asks for A."),
            skill("beta", "Run the beta flow when the user asks for B."),
            skill("ghost-hub", "Hub-invoked; must not be advertised or dispatched.")));

    private SkillRouter router() {
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put("alpha", m -> reply("alpha"));
        flows.put("beta", m -> reply("beta"));
        return new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the demo agent.",
                "Decide: run a skill or just talk?",
                flows, m -> reply("chat"));
    }

    @Test
    void routesToTheFlowTheLlmNames() {
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(resp("{\"action\":\"skill\",\"name\":\"beta\"}"));
        });

        assertThat(route("do B please").text()).isEqualTo("beta");
        // system(manifest.body) + system(classifier prompt) + user(text)
        assertThat(seen.get().messages()).hasSize(3);
    }

    @Test
    void chatDecisionUsesTheChatFallback() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                resp("{\"action\":\"chat\",\"text\":\"How can I help?\"}")));

        assertThat(route("hello").text()).isEqualTo("chat");
    }

    @Test
    void nonJsonProseFallsBackToChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(resp("Sorry, I didn't get that.")));

        assertThat(route("???").text()).isEqualTo("chat");
    }

    @Test
    void unknownSkillNameFallsBackToChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                resp("{\"action\":\"skill\",\"name\":\"nope\"}")));

        assertThat(route("whatever").text()).isEqualTo("chat");
    }

    @Test
    void hubOnlySkillIsNeitherAdvertisedNorDispatched() {
        // The model names a loaded-but-unmapped skill → must not dispatch it; and it is not in the prompt.
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                resp("{\"action\":\"skill\",\"name\":\"ghost-hub\"}")));

        assertThat(route("greet someone").text()).isEqualTo("chat");

        assertThat(router().buildClassifierPrompt())
                .contains("alpha").contains("beta").doesNotContain("ghost-hub");
    }

    @Test
    void toolDecisionFallsBackToChat() {
        // Skills-only agents advertise no tools; if a small model emits one anyway, stay total.
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                resp("{\"action\":\"tool\",\"name\":\"x\"}")));

        assertThat(route("x").text()).isEqualTo("chat");
    }

    @Test
    void blankMessageSkipsTheLlm() {
        assertThat(route("   ").text()).isEqualTo("chat");
        verify(llm, never()).chat(any());
    }

    @Test
    void llmErrorFallsBackToChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.error(new RuntimeException("gateway down")));

        assertThat(route("do A").text()).isEqualTo("chat");
    }

    @Test
    void noRoutableSkillLoadedSkipsTheLlmAndChats() {
        // A dispatch map whose keys aren't in the registry → empty route set → straight to chat.
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put("missing", m -> reply("missing"));
        SkillRouter router = new SkillRouter(llm, skills, classifier, manifest,
                "intro", "decide", flows, m -> reply("chat"));

        assertThat(router.route(msg("anything")).block().text()).isEqualTo("chat");
        verify(llm, never()).chat(any());
    }

    /** Run the default 2-flow router and unwrap the reactive result. */
    private IntentResponse route(String text) {
        return router().route(msg(text)).block();
    }

    private Mono<IntentResponse> reply(String tag) {
        return Mono.just(new IntentResponse("demo", tag, null));
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "0.1.0", "demo", List.of(), List.of("en"), "body");
    }

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse resp(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
