package dev.fedorov.ailife.agents.stylist.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.stylist.chat.StylistChat;
import dev.fedorov.ailife.agents.stylist.flow.GapAnalyst;
import dev.fedorov.ailife.agents.stylist.flow.StylistAdvisor;
import dev.fedorov.ailife.agents.stylist.flow.WardrobeAuditor;
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
 * Unit tests for {@link StylistIntentRouter} — a mock {@link LlmClient} drives each classifier branch and
 * the flows are mocked, so we assert exactly which flow the router dispatched to (the parity check for the
 * cue→classifier migration, #475). Mirrors the notes/creator/docs/nutrition/briefing router tests: stylist
 * has three text-routable intent skills ({@code wardrobe-auditor}/{@code gap-analyst}/{@code
 * capsule-advisor}); the two photo-gated skills ({@code wardrobe-cataloguer}/{@code style-analyst}) are
 * loaded to prove they are NOT routed here (they are handled by the attachment pre-check in
 * {@code IntentController}).
 */
class StylistIntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final WardrobeAuditor auditor = mock(WardrobeAuditor.class);
    private final GapAnalyst gapAnalyst = mock(GapAnalyst.class);
    private final StylistAdvisor advisor = mock(StylistAdvisor.class);
    private final StylistChat chat = mock(StylistChat.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final AgentManifest manifest = new AgentManifest(
            "stylist", "test", "0.0.1", 0, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the stylist agent for the ai-life system.");
    // wardrobe-cataloguer + style-analyst are loaded (photo-gated skills) to prove they are NOT routed here.
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("wardrobe-auditor", "Audit the catalogued wardrobe: keep / question / remove per garment."),
            skill("gap-analyst", "Find the gaps in the wardrobe — what to buy, priority and price tier."),
            skill("capsule-advisor", "Assemble an outfit capsule from the catalogued wardrobe."),
            skill("wardrobe-cataloguer", "Extract a garment description from a clothing photo (photo-gated)."),
            skill("style-analyst", "Analyse a person from a self-photo into a style board (photo-gated).")));

    private final StylistIntentRouter router = new StylistIntentRouter(
            llm, skills, classifier, manifest, auditor, gapAnalyst, advisor, chat);

    @Test
    void routesToWardrobeAuditor() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"wardrobe-auditor\"}")));
        when(auditor.audit(any())).thenReturn(Mono.just(sentinel("auditor")));

        StepVerifier.create(router.route(msg("сделай ревизию гардероба")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("auditor"))
                .verifyComplete();
        verify(auditor).audit(any());
    }

    @Test
    void routesToGapAnalyst() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"gap-analyst\"}")));
        when(gapAnalyst.analyse(any())).thenReturn(Mono.just(sentinel("gap")));

        StepVerifier.create(router.route(msg("что мне докупить в гардероб?")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("gap"))
                .verifyComplete();
        verify(gapAnalyst).analyse(any());
    }

    @Test
    void routesToCapsuleAdvisor() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"capsule-advisor\"}")));
        when(advisor.advise(any())).thenReturn(Mono.just(sentinel("advisor")));

        StepVerifier.create(router.route(msg("собери капсулу на осень")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("advisor"))
                .verifyComplete();
        verify(advisor).advise(any());
    }

    @Test
    void chatDecisionFallsBackToStylistChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"chat\",\"text\":\"Чем помочь со стилем?\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("привет")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        verify(auditor, never()).audit(any());
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
    void photoGatedSkillNameFallsBackToChatAndIsNotAdvertised() {
        // wardrobe-cataloguer is a photo-gated skill, not in the dispatch map → the router must not dispatch it.
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"wardrobe-cataloguer\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("добавь эту рубашку в гардероб")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        assertThat(router.buildClassifierPrompt())
                .contains("wardrobe-auditor").contains("capsule-advisor")
                .doesNotContain("wardrobe-cataloguer").doesNotContain("style-analyst");
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

        StepVerifier.create(router.route(msg("собери капсулу")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "0.1.0", "stylist", List.of(), List.of("en", "ru"), "body");
    }

    private IntentResponse sentinel(String tag) {
        return new IntentResponse("stylist", tag, null);
    }

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
