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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TravelIntentRouter} — a mock {@link LlmClient} drives each classifier branch and the
 * flows are mocked, so we assert exactly which flow the router dispatched to (the parity check for the
 * cue→classifier migration, #475). Mirrors the sibling router tests, plus the travel-specific map-link
 * import folded into the chat fallback: a bare map link with a chat decision imports the link, while a plain
 * message chats. The route-file attachment import stays a pre-check in {@code IntentController} (not tested
 * here). {@code packing-list} routes to the deterministic {@link PackingFlow}; its SKILL.md is a
 * routing-descriptor.
 */
class TravelIntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final TravelProfiler profiler = mock(TravelProfiler.class);
    private final WalletFlow wallet = mock(WalletFlow.class);
    private final PackingFlow packing = mock(PackingFlow.class);
    private final TripComposer composer = mock(TripComposer.class);
    private final RouteFlow route = mock(RouteFlow.class);
    private final TravelChat chat = mock(TravelChat.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final AgentManifest manifest = new AgentManifest(
            "travel", "test", "0.0.1", 0, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the travel agent for the ai-life system.");
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("travel-profiler", "Extract a person's travel preferences from a message."),
            skill("trip-wallet", "Record one trip-wallet action: create / fund / exchange / spend / tally."),
            skill("packing-list", "Build a categorized packing list for the active trip."),
            skill("trip-composer", "Write one vacation plan from pre-gathered material.")));

    private final TravelIntentRouter router = new TravelIntentRouter(
            llm, skills, classifier, manifest, profiler, wallet, packing, composer, route, chat);

    @Test
    void routesToTravelProfiler() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"travel-profiler\"}")));
        when(profiler.setProfile(any())).thenReturn(Mono.just(sentinel("profiler")));

        StepVerifier.create(router.route(msg("летаем из Москвы, любим пляж")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("profiler"))
                .verifyComplete();
        verify(profiler).setProfile(any());
    }

    @Test
    void routesToTripWallet() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"trip-wallet\"}")));
        when(wallet.handle(any())).thenReturn(Mono.just(sentinel("wallet")));

        StepVerifier.create(router.route(msg("потратил 2000 бат на ужин")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("wallet"))
                .verifyComplete();
        verify(wallet).handle(any());
    }

    @Test
    void routesToPackingList() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"packing-list\"}")));
        when(packing.handle(any())).thenReturn(Mono.just(sentinel("packing")));

        StepVerifier.create(router.route(msg("а что кинуть в чемодан?")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("packing"))
                .verifyComplete();
        verify(packing).handle(any());
    }

    @Test
    void routesToTripComposer() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"trip-composer\"}")));
        when(composer.plan(any())).thenReturn(Mono.just(sentinel("composer")));

        StepVerifier.create(router.route(msg("хочу на море в сентябре, бюджет 200к")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("composer"))
                .verifyComplete();
        verify(composer).plan(any());
    }

    @Test
    void chatDecisionWithoutLinkFallsBackToTravelChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"chat\",\"text\":\"Чем помочь с путешествиями?\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("привет")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        verify(route, never()).handleLink(any(), any());
    }

    @Test
    void chatDecisionWithMapLinkImportsTheLink() {
        // A bare map link with no classified intent → the fallback imports it (RT-d2), not a chat reply.
        String url = "https://yandex.ru/maps/?ll=37.62,55.75";
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("{\"action\":\"chat\"}")));
        when(route.handleLink(any(), eq(url))).thenReturn(Mono.just(sentinel("route-link")));

        StepVerifier.create(router.route(msg("вот это место: " + url)))
                .assertNext(r -> assertThat(r.text()).isEqualTo("route-link"))
                .verifyComplete();
        verify(route).handleLink(any(), eq(url));
        verify(chat, never()).reply(any());
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

        StepVerifier.create(router.route(msg("хочу в отпуск")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "0.1.0", "travel", List.of(), List.of("en", "ru"), "body");
    }

    private IntentResponse sentinel(String tag) {
        return new IntentResponse("travel", tag, null);
    }

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
