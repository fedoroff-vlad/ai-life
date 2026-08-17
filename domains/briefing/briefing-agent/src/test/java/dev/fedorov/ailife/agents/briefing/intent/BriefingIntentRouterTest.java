package dev.fedorov.ailife.agents.briefing.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.briefing.chat.BriefingChat;
import dev.fedorov.ailife.agents.briefing.flow.BriefingComposer;
import dev.fedorov.ailife.agents.briefing.profile.BriefingProfiler;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingIntentRouter} — a mock {@link LlmClient} drives each classifier branch and
 * the flows are mocked, so we assert exactly which flow the router dispatched to (the parity check for the
 * cue→classifier migration, #475). Mirrors the notes/creator/docs/nutrition router tests: briefing has two
 * text-routable intent skills ({@code briefing-composer} = digest now, {@code briefing-profiler} = set/
 * change preferences), plus the shared soft-fail-to-chat behaviour.
 */
class BriefingIntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final BriefingComposer composer = mock(BriefingComposer.class);
    private final BriefingProfiler profiler = mock(BriefingProfiler.class);
    private final BriefingChat chat = mock(BriefingChat.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final AgentManifest manifest = new AgentManifest(
            "briefing", "test", "0.0.1", 0, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the briefing agent for the ai-life system.");
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("briefing-composer", "Write one short morning briefing from pre-gathered material."),
            skill("briefing-profiler", "Extract a person's morning-briefing preferences from a message.")));

    private final BriefingIntentRouter router = new BriefingIntentRouter(
            llm, skills, classifier, manifest, composer, profiler, chat);

    @Test
    void routesToBriefingComposer() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"briefing-composer\"}")));
        when(composer.digest(any())).thenReturn(Mono.just(sentinel("composer")));

        StepVerifier.create(router.route(msg("собери мне брифинг на сегодня")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("composer"))
                .verifyComplete();
        verify(composer).digest(any());
        verify(profiler, never()).setProfile(any());
    }

    @Test
    void routesToBriefingProfiler() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"briefing-profiler\"}")));
        when(profiler.setProfile(any())).thenReturn(Mono.just(sentinel("profiler")));

        StepVerifier.create(router.route(msg("настрой утренний брифинг: погода в Москве, в 8 утра")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("profiler"))
                .verifyComplete();
        verify(profiler).setProfile(any());
        verify(composer, never()).digest(any());
    }

    @Test
    void chatDecisionFallsBackToBriefingChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"chat\",\"text\":\"Чем помочь с брифингом?\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("привет")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        verify(composer, never()).digest(any());
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
    void unknownSkillNameFallsBackToChat() {
        // A skill name not in the dispatch map → the router must not dispatch it (stays total).
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"weather-forecaster\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("какая погода?")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        assertThat(router.buildClassifierPrompt())
                .contains("briefing-composer").contains("briefing-profiler");
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

        StepVerifier.create(router.route(msg("собери брифинг")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "0.1.0", "briefing", List.of(), List.of("en", "ru"), "body");
    }

    private IntentResponse sentinel(String tag) {
        return new IntentResponse("briefing", tag, null);
    }

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
