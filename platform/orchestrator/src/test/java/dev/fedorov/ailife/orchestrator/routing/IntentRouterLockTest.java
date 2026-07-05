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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
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
 * Unit tests for {@link IntentRouter}'s Stage-4 route-lock lifecycle — mock
 * {@link ConversationStateClient}, {@link LlmIntentClassifier} and the agent dispatch map exercise
 * the resume / classify / lock-write / lock-clear branches without an external service.
 */
class IntentRouterLockTest {

    private final LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
    private final ConversationStateClient conversationState = mock(ConversationStateClient.class);
    private final Agent calendar = mock(Agent.class);
    private final Agent finance = mock(Agent.class);
    private final Agent echo = mock(Agent.class);
    private final Map<String, Agent> agents = Map.of("calendar", calendar, "finance", finance, "echo", echo);
    private final ObjectMapper json = new ObjectMapper();

    private final IntentRouter router = new IntentRouter(agents, classifier, conversationState);

    private static NormalizedMessage msg() {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "да", java.util.List.of(), "telegram", "1", Instant.now());
    }

    private static ConversationStateDto lockedTo(String agent, tools.jackson.databind.JsonNode pending) {
        return new ConversationStateDto(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "telegram", agent, pending, Instant.now().plusSeconds(600), Instant.now());
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
        verify(classifier, never()).classify(any());
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
    void noLockClassifiesAndDoesNotTouchLockWhenNoPendingAction() {
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        verify(classifier).classify(any());
        verify(finance).handle(any());
        // Fresh turn, no pending action → no lock write, no clear.
        verify(conversationState, never()).lock(any(), any(), any(), any(), any());
        verify(conversationState, never()).clear(any(), any(), any());
    }

    @Test
    void handleReturningPendingActionLocksConversation() {
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any())).thenReturn(Mono.just("finance"));
        var pending = json.createObjectNode().put("flow", "receipt-confirm");
        when(finance.handle(any()))
                .thenReturn(Mono.just(new IntentResponse("finance", "confirm?", "m", pending)));
        when(conversationState.lock(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(router.route(msg())).expectNextCount(1).verifyComplete();

        verify(conversationState).lock(any(), any(), eq("telegram"), eq("finance"), eq(pending));
    }

    @Test
    void lockToUnknownAgentFallsThroughToClassifier() {
        when(conversationState.activeState(any(), any(), any()))
                .thenReturn(Mono.just(lockedTo("ghost-agent", null)));
        when(classifier.classify(any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        verify(classifier).classify(any());
        verify(calendar, never()).resume(any());
    }
}
