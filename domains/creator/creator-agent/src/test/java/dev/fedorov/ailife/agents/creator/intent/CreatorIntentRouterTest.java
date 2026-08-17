package dev.fedorov.ailife.agents.creator.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.creator.chat.CreatorChat;
import dev.fedorov.ailife.agents.creator.flow.ContentStrategist;
import dev.fedorov.ailife.agents.creator.profile.CreatorProfiler;
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
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CreatorIntentRouter} — a mock {@link LlmClient} drives each classifier branch and
 * the flows are mocked, so we assert exactly which flow the router dispatched to (the parity check for the
 * cue→classifier migration, #475). Mirrors notes/tasks router tests.
 */
class CreatorIntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final CreatorProfiler creatorProfiler = mock(CreatorProfiler.class);
    private final ContentStrategist strategist = mock(ContentStrategist.class);
    private final CreatorChat chat = mock(CreatorChat.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final AgentManifest manifest = new AgentManifest(
            "creator", "test", "0.0.1", 0, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the creator agent for the ai-life system.");
    // Includes greeting-drafter (empty triggers, hub-invoked) to prove it is NOT advertised/dispatched.
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("creator-profiler", "Extracts a person's creator track (niche, audience, tone)."),
            skill("content-strategist", "Synthesises a content plan from freshly-gathered trends."),
            skill("greeting-drafter", "Drafts a greeting; invoked over the hub, not by a user.")));

    private final CreatorIntentRouter router =
            new CreatorIntentRouter(llm, skills, classifier, manifest, creatorProfiler, strategist, chat);

    @Test
    void routesToProfilerWhenLlmPicksCreatorProfiler() {
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("{\"action\":\"skill\",\"name\":\"creator-profiler\"}"));
        });
        when(creatorProfiler.setProfile(any())).thenReturn(Mono.just(sentinel("profiler")));

        StepVerifier.create(router.route(msg("мой контент про английский для айтишников")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("profiler"))
                .verifyComplete();

        verify(creatorProfiler).setProfile(any());
        verify(strategist, never()).run(any());
        assertThat(seen.get().messages()).hasSize(3);
        // greeting-drafter must not be advertised (hub-invoked, excluded from the route set).
        String prompt = router.buildClassifierPrompt();
        assertThat(prompt).contains("creator-profiler").contains("content-strategist")
                .doesNotContain("greeting-drafter");
    }

    @Test
    void routesToStrategistWhenLlmPicksContentStrategist() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"content-strategist\"}")));
        when(strategist.run(any())).thenReturn(Mono.just(sentinel("strategist")));

        StepVerifier.create(router.route(msg("какие тренды на этой неделе, дай идеи")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("strategist"))
                .verifyComplete();

        verify(strategist).run(any());
        verify(creatorProfiler, never()).setProfile(any());
    }

    @Test
    void chatDecisionFallsBackToCreatorChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"chat\",\"text\":\"Чем помочь с контентом?\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("привет")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
        verify(strategist, never()).run(any());
        verify(creatorProfiler, never()).setProfile(any());
    }

    @Test
    void hubOnlySkillNameFallsBackToChat() {
        // If the model somehow names the hub-invoked greeting-drafter, the router must not dispatch it.
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"greeting-drafter\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("поздравь маму")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
    }

    @Test
    void nonJsonProseFallsBackToChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("Извините, не понял.")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("???")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
    }

    @Test
    void blankMessageSkipsTheLlmAndChats() {
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("   ")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
        verify(llm, never()).chat(any());
    }

    @Test
    void llmErrorFallsBackToChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.error(new RuntimeException("gateway down")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("сделай контент-план")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();

        verify(chat).reply(any());
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "0.1.0", "creator", List.of(), List.of("en", "ru"), "body");
    }

    private IntentResponse sentinel(String tag) {
        return new IntentResponse("creator", tag, null);
    }

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
