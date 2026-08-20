package dev.fedorov.ailife.orchestrator.routing;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import dev.fedorov.ailife.orchestrator.agent.Agent;
import dev.fedorov.ailife.orchestrator.conversation.ConversationStateClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IntentRouter}'s Stage-4 route-lock lifecycle — mock
 * {@link ConversationStateClient}, {@link LlmIntentClassifier} and the agent dispatch map exercise
 * the resume / classify / lock-write / lock-clear branches without an external service.
 */
class IntentRouterLockTest {

    private final LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
    private final ConversationStateClient conversationState = mock(ConversationStateClient.class);
    private final ExplainResponder explainResponder = mock(ExplainResponder.class);
    private final Agent calendar = mock(Agent.class);
    private final Agent finance = mock(Agent.class);
    private final Agent echo = mock(Agent.class);
    private final Map<String, Agent> agents = Map.of("calendar", calendar, "finance", finance, "echo", echo);
    private final ObjectMapper json = new ObjectMapper();

    private final IntentRouter router =
            new IntentRouter(agents, classifier, conversationState, explainResponder);

    private static NormalizedMessage msg() {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "да", java.util.List.of(), "telegram", "1", Instant.now());
    }

    private static ConversationStateDto lockedTo(String agent, tools.jackson.databind.JsonNode pending) {
        return new ConversationStateDto(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "telegram", agent, pending, null, null, null, Instant.now().plusSeconds(600), Instant.now());
    }

    /** A conversation with a recorded last-route (misroute-repair #484) and no active lock. */
    private static ConversationStateDto lastRoutedTo(String agent, String text) {
        return new ConversationStateDto(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "telegram", null, null, agent, text, null, Instant.now().plusSeconds(180), Instant.now());
    }

    @Test
    void activeLockResumesLockedAgentBypassingClassifierAndClearsOnResolve() {
        var pending = json.createObjectNode().put("flow", "x");
        when(conversationState.activeState(any(), any(), eq("telegram")))
                .thenReturn(Mono.just(lockedTo("calendar", pending)));
        when(calendar.resume(any())).thenReturn(Mono.just(new IntentResponse("calendar", "done", "m")));
        when(conversationState.clear(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("calendar"))
                .verifyComplete();

        // Resumed (not handled), classifier never consulted, resolved → lock cleared.
        verify(calendar).resume(any(ResumeRequest.class));
        verify(calendar, never()).handle(any());
        verify(classifier, never()).classify(any(), any());
        verify(conversationState).clear(any(), any(), eq("telegram"));
    }

    @Test
    void resumeReturningPendingActionReLocks() {
        var pending = json.createObjectNode().put("flow", "x");
        when(conversationState.activeState(any(), any(), any()))
                .thenReturn(Mono.just(lockedTo("calendar", pending)));
        var stillPending = json.createObjectNode().put("flow", "x").put("step", 2);
        when(calendar.resume(any()))
                .thenReturn(Mono.just(new IntentResponse("calendar", "and?", "m", stillPending)));
        when(conversationState.lock(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg())).expectNextCount(1).verifyComplete();

        verify(conversationState).lock(any(), any(), eq("telegram"), eq("calendar"), any());
        verify(conversationState, never()).clear(any(), any(), any());
    }

    @Test
    void noLockClassifiesAndRecordsLastRouteWhenNoPendingAction() {
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any(), any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));
        when(conversationState.recordLastRoute(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        NormalizedMessage m = msg();
        StepVerifier.create(router.route(m))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        // No active state → classify with a null prior route.
        verify(classifier).classify(any(), isNull());
        verify(finance).handle(any());
        // Fresh specialist turn, no pending action → last-route recorded, no lock/clear.
        verify(conversationState).recordLastRoute(
                eq(m.householdId()), eq(m.userId()), eq("telegram"), eq("finance"), eq(m.text()));
        verify(conversationState, never()).lock(any(), any(), any(), any(), any());
        verify(conversationState, never()).clear(any(), any(), any());
    }

    @Test
    void handleReturningPendingActionLocksConversation() {
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any(), any())).thenReturn(Mono.just("finance"));
        var pending = json.createObjectNode().put("flow", "receipt-confirm");
        when(finance.handle(any()))
                .thenReturn(Mono.just(new IntentResponse("finance", "confirm?", "m", pending)));
        when(conversationState.lock(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg())).expectNextCount(1).verifyComplete();

        verify(conversationState).lock(any(), any(), eq("telegram"), eq("finance"), eq(pending));
        // An open question locks — it does not also record a last-route.
        verify(conversationState, never()).recordLastRoute(any(), any(), any(), any(), any());
    }

    @Test
    void lockToUnknownAgentFallsThroughToClassifier() {
        when(conversationState.activeState(any(), any(), any()))
                .thenReturn(Mono.just(lockedTo("ghost-agent", null)));
        when(classifier.classify(any(), any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));
        when(conversationState.recordLastRoute(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        verify(classifier).classify(any(), any());
        verify(calendar, never()).resume(any());
    }

    @Test
    void correctionTurnPassesPriorRouteToClassifierAndReRoutes() {
        // Last turn routed "запиши купить молоко" to notes; now the owner corrects: "не то, это задача".
        when(conversationState.activeState(any(), any(), any()))
                .thenReturn(Mono.just(lastRoutedTo("calendar", "запиши купить молоко")));
        // The classifier, given the prior route, re-routes the correction to finance.
        when(classifier.classify(any(), any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));
        when(conversationState.recordLastRoute(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        // The recorded last-route was handed to the classifier as correction context…
        ArgumentCaptor<LlmIntentClassifier.PriorRoute> prior =
                ArgumentCaptor.forClass(LlmIntentClassifier.PriorRoute.class);
        verify(classifier).classify(any(), prior.capture());
        assertThat(prior.getValue()).isNotNull();
        assertThat(prior.getValue().agent()).isEqualTo("calendar");
        assertThat(prior.getValue().originalText()).isEqualTo("запиши купить молоко");
        // …and the corrected route is itself recorded, so a further correction still works.
        verify(conversationState).recordLastRoute(any(), any(), eq("telegram"), eq("finance"), any());
    }

    @Test
    void explainTurnAnswersFromPriorRouteWithoutDispatchingOrRecording() {
        // Last turn routed a request to finance; now the owner asks "почему ты так сделал?".
        when(conversationState.activeState(any(), any(), any()))
                .thenReturn(Mono.just(lastRoutedTo("finance", "сколько я потратил на еду")));
        when(classifier.classify(any(), any())).thenReturn(Mono.just("explain"));
        when(explainResponder.explain(any(), any()))
                .thenReturn(Mono.just(new IntentResponse("explain", "Это ушло финансовому агенту.", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("explain"))
                .verifyComplete();

        // Answered from the remembered prior route — no domain agent dispatched…
        ArgumentCaptor<LlmIntentClassifier.PriorRoute> prior =
                ArgumentCaptor.forClass(LlmIntentClassifier.PriorRoute.class);
        verify(explainResponder).explain(any(), prior.capture());
        assertThat(prior.getValue().agent()).isEqualTo("finance");
        verify(finance, never()).handle(any());
        // …and the meta-query does NOT overwrite last-route (a later correction still repairs the original).
        verify(conversationState, never()).recordLastRoute(any(), any(), any(), any(), any());
    }

    @Test
    void explainWithoutPriorRouteFallsThroughToEcho() {
        // No fresh prior route → an 'explain' outcome has nothing to explain; it degrades to echo.
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any(), isNull())).thenReturn(Mono.just("explain"));
        when(echo.handle(any())).thenReturn(Mono.just(new IntentResponse("echo", "hi", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("echo"))
                .verifyComplete();

        verify(explainResponder, never()).explain(any(), any());
        verify(echo).handle(any());
    }

    @Test
    void echoRouteDoesNotRecordLastRoute() {
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any(), any())).thenReturn(Mono.just("echo"));
        when(echo.handle(any())).thenReturn(Mono.just(new IntentResponse("echo", "hi", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("echo"))
                .verifyComplete();

        // A correction after small talk is meaningless — echo turns are not remembered.
        verify(conversationState, never()).recordLastRoute(any(), any(), any(), any(), any());
    }
}
