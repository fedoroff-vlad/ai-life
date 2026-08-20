package dev.fedorov.ailife.orchestrator.routing;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.agent.UndoHandle;
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
 * Unit tests for {@link IntentRouter}'s Stage-4 route-lock lifecycle plus the misroute-repair (#484),
 * why-trace (#485) and undo (#486) branches — mock {@link ConversationStateClient},
 * {@link LlmIntentClassifier}, {@link ExplainResponder} and the agent dispatch map exercise the
 * resume / classify / lock-write / lock-clear / undo branches without an external service.
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
        return lastRoutedTo(agent, text, null);
    }

    /** As {@link #lastRoutedTo(String, String)} but with a remembered agent trace (why-trace #485/G2). */
    private static ConversationStateDto lastRoutedTo(String agent, String text, String trace) {
        return new ConversationStateDto(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "telegram", null, null, agent, text, trace, null, null, null,
                Instant.now().plusSeconds(180), Instant.now());
    }

    /** A conversation holding an undoable last-mutation (#486), optionally alongside a last-route. */
    private ConversationStateDto withMutation(String routeAgent, String routeText,
                                              String mutAgent, String mutDesc) {
        var payload = json.createObjectNode().put("action", "delete_task").put("taskId", "t-1");
        return new ConversationStateDto(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "telegram", null, null, routeAgent, routeText, null,
                mutAgent, payload, mutDesc, Instant.now().plusSeconds(180), Instant.now());
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
        verify(classifier, never()).classify(any(), any(), any());
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
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));
        when(conversationState.recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(Mono.empty());

        NormalizedMessage m = msg();
        StepVerifier.create(router.route(m))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        // No active state → classify with a null prior route and no undoable.
        verify(classifier).classify(any(), isNull(), isNull());
        verify(finance).handle(any());
        // Fresh specialist turn, no pending action, no undo handle → last-route recorded (no trace/mutation).
        verify(conversationState).recordLastRoute(
                eq(m.householdId()), eq(m.userId()), eq("telegram"), eq("finance"), eq(m.text()), isNull(),
                isNull(), isNull(), isNull());
        verify(conversationState, never()).lock(any(), any(), any(), any(), any());
        verify(conversationState, never()).clear(any(), any(), any());
    }

    @Test
    void handleReturningPendingActionLocksConversation() {
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("finance"));
        var pending = json.createObjectNode().put("flow", "receipt-confirm");
        when(finance.handle(any()))
                .thenReturn(Mono.just(new IntentResponse("finance", "confirm?", "m", pending)));
        when(conversationState.lock(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg())).expectNextCount(1).verifyComplete();

        verify(conversationState).lock(any(), any(), eq("telegram"), eq("finance"), eq(pending));
        // An open question locks — it does not also record a last-route.
        verify(conversationState, never()).recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void lockToUnknownAgentFallsThroughToClassifier() {
        when(conversationState.activeState(any(), any(), any()))
                .thenReturn(Mono.just(lockedTo("ghost-agent", null)));
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));
        when(conversationState.recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        verify(classifier).classify(any(), any(), any());
        verify(calendar, never()).resume(any());
    }

    @Test
    void correctionTurnPassesPriorRouteToClassifierAndReRoutes() {
        // Last turn routed "запиши купить молоко" to notes; now the owner corrects: "не то, это задача".
        when(conversationState.activeState(any(), any(), any()))
                .thenReturn(Mono.just(lastRoutedTo("calendar", "запиши купить молоко")));
        // The classifier, given the prior route, re-routes the correction to finance.
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));
        when(conversationState.recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        // The recorded last-route was handed to the classifier as correction context…
        ArgumentCaptor<LlmIntentClassifier.PriorRoute> prior =
                ArgumentCaptor.forClass(LlmIntentClassifier.PriorRoute.class);
        verify(classifier).classify(any(), prior.capture(), any());
        assertThat(prior.getValue()).isNotNull();
        assertThat(prior.getValue().agent()).isEqualTo("calendar");
        assertThat(prior.getValue().originalText()).isEqualTo("запиши купить молоко");
        // …and the corrected route is itself recorded, so a further correction still works.
        verify(conversationState).recordLastRoute(any(), any(), eq("telegram"), eq("finance"), any(), any(),
                any(), any(), any());
    }

    @Test
    void explainTurnAnswersFromPriorRouteWithoutDispatchingOrRecording() {
        // Last turn routed a request to finance and remembered what it did; now the owner asks "почему?".
        when(conversationState.activeState(any(), any(), any()))
                .thenReturn(Mono.just(lastRoutedTo("finance", "сколько я потратил на еду",
                        "read: finance brief")));
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("explain"));
        when(explainResponder.explain(any(), any()))
                .thenReturn(Mono.just(new IntentResponse("explain", "Это ушло финансовому агенту.", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("explain"))
                .verifyComplete();

        // Answered from the remembered prior route (agent + its trace) — no domain agent dispatched…
        ArgumentCaptor<LlmIntentClassifier.PriorRoute> prior =
                ArgumentCaptor.forClass(LlmIntentClassifier.PriorRoute.class);
        verify(explainResponder).explain(any(), prior.capture());
        assertThat(prior.getValue().agent()).isEqualTo("finance");
        assertThat(prior.getValue().trace()).isEqualTo("read: finance brief");
        verify(finance, never()).handle(any());
        // …and the meta-query does NOT overwrite last-route (a later correction still repairs the original).
        verify(conversationState, never()).recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void agentContributedTraceIsRecordedWithLastRoute() {
        // An agent opts into the why-trace by attaching a payload-free line via withTrace (#485/G2).
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(
                new IntentResponse("finance", "ok", "m").withTrace("read: finance brief; wrote: logged an expense")));
        when(conversationState.recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg())).expectNextCount(1).verifyComplete();

        // The trace rides along with the last-route so a later "почему" can fold it in.
        verify(conversationState).recordLastRoute(any(), any(), eq("telegram"), eq("finance"), any(),
                eq("read: finance brief; wrote: logged an expense"), any(), any(), any());
    }

    @Test
    void explainWithoutPriorRouteFallsThroughToEcho() {
        // No fresh prior route → an 'explain' outcome has nothing to explain; it degrades to echo.
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any(), isNull(), isNull())).thenReturn(Mono.just("explain"));
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
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("echo"));
        when(echo.handle(any())).thenReturn(Mono.just(new IntentResponse("echo", "hi", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("echo"))
                .verifyComplete();

        // A correction after small talk is meaningless — echo turns are not remembered.
        verify(conversationState, never()).recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    // ---- Undo (#486, Track H) ------------------------------------------------------------------

    @Test
    void freshWriteWithUndoHandleRecordsLastMutation() {
        // A terminal write opts into undo by attaching a handle via withUndo.
        var undoPayload = json.createObjectNode().put("action", "delete_task").put("taskId", "t-1");
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")
                .withUndo(new UndoHandle("трату 1500 ₽", undoPayload))));
        when(conversationState.recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg())).expectNextCount(1).verifyComplete();

        // The undo handle is persisted as the conversation's last_mutation alongside the last-route.
        verify(conversationState).recordLastRoute(any(), any(), eq("telegram"), eq("finance"), any(), any(),
                eq("finance"), eq(undoPayload), eq("трату 1500 ₽"));
    }

    @Test
    void readTurnCarriesForwardAnUnconsumedMutation() {
        // Prior turn left an undoable mutation; this is a plain read (no new undo handle) — the mutation
        // must survive so a following "отмени последнее" still works.
        var state = withMutation("finance", "сколько потратил", "tasks", "задачу «купить молоко»");
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.just(state));
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "1500 ₽", "m")));
        when(conversationState.recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg())).expectNextCount(1).verifyComplete();

        // Carried forward: the prior mutation is re-persisted though this turn produced no new handle.
        verify(conversationState).recordLastRoute(any(), any(), eq("telegram"), eq("finance"), any(), any(),
                eq("tasks"), any(), eq("задачу «купить молоко»"));
    }

    @Test
    void undoableStateOffersTheUndoOutcomeToTheClassifier() {
        var state = withMutation(null, null, "tasks", "задачу «купить молоко»");
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.just(state));
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("echo"));
        when(echo.handle(any())).thenReturn(Mono.just(new IntentResponse("echo", "hi", "m")));

        StepVerifier.create(router.route(msg())).expectNextCount(1).verifyComplete();

        // The undoable (with its description) is offered to the classifier so it may return 'undo'.
        ArgumentCaptor<LlmIntentClassifier.Undoable> undoable =
                ArgumentCaptor.forClass(LlmIntentClassifier.Undoable.class);
        verify(classifier).classify(any(), any(), undoable.capture());
        assertThat(undoable.getValue()).isNotNull();
        assertThat(undoable.getValue().description()).isEqualTo("задачу «купить молоко»");
    }

    @Test
    void undoTurnReversesLastMutationViaInvokeAndClearsIt() {
        var state = withMutation(null, null, "tasks", "задачу «купить молоко»");
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.just(state));
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("undo"));
        // The tasks-agent-would-be here: a mock returning a user-facing confirmation.
        var result = json.createObjectNode().put("message", "Удалил задачу «купить молоко».");
        when(finance.invoke(any())).thenReturn(Mono.empty()); // guard: not the recording agent
        Agent tasks = mock(Agent.class);
        Map<String, Agent> withTasks = Map.of("calendar", calendar, "finance", finance, "echo", echo, "tasks", tasks);
        IntentRouter r = new IntentRouter(withTasks, classifier, conversationState, explainResponder);
        when(tasks.invoke(any())).thenReturn(Mono.just(AgentActionResult.ok(result)));
        when(conversationState.clear(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(r.route(msg()))
                .assertNext(resp -> {
                    assertThat(resp.agent()).isEqualTo("undo");
                    assertThat(resp.text()).isEqualTo("Удалил задачу «купить молоко».");
                })
                .verifyComplete();

        // Dispatched the stored handle as an 'undo' action to the recording agent…
        ArgumentCaptor<AgentActionRequest> req = ArgumentCaptor.forClass(AgentActionRequest.class);
        verify(tasks).invoke(req.capture());
        assertThat(req.getValue().action()).isEqualTo("undo");
        assertThat(req.getValue().targetAgent()).isEqualTo("tasks");
        assertThat(req.getValue().args().path("taskId").asString()).isEqualTo("t-1");
        // …and consumed the mutation (no last-route to preserve here → whole state cleared).
        verify(tasks, never()).handle(any());
        verify(conversationState).clear(any(), any(), eq("telegram"));
    }

    @Test
    void irreversibleUndoIsSurfacedHonestlyAndMutationKept() {
        var state = withMutation(null, null, "finance", "перевод");
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.just(state));
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("undo"));
        when(finance.invoke(any()))
                .thenReturn(Mono.just(AgentActionResult.error("Это действие нельзя отменить.")));

        StepVerifier.create(router.route(msg()))
                .assertNext(resp -> {
                    assertThat(resp.agent()).isEqualTo("undo");
                    assertThat(resp.text()).isEqualTo("Это действие нельзя отменить.");
                })
                .verifyComplete();

        // Honest failure → the mutation is left in place (nothing reversed), never a silent no-op.
        verify(conversationState, never()).clear(any(), any(), any());
        verify(conversationState, never()).recordLastRoute(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void undoWithUnavailableRecordingAgentIsSurfacedHonestly() {
        // last_mutation names an agent no longer in the dispatch map.
        var state = withMutation(null, null, "ghost", "что-то");
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.just(state));
        when(classifier.classify(any(), any(), any())).thenReturn(Mono.just("undo"));

        StepVerifier.create(router.route(msg()))
                .assertNext(resp -> {
                    assertThat(resp.agent()).isEqualTo("undo");
                    assertThat(resp.text()).contains("недоступен");
                })
                .verifyComplete();

        verify(conversationState, never()).clear(any(), any(), any());
    }
}
