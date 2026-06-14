package dev.fedorov.ailife.orchestrator.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import dev.fedorov.ailife.orchestrator.agent.Agent;
import dev.fedorov.ailife.orchestrator.conversation.ConversationStateClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IntentRouter}'s Stage-4 route-lock behaviour — mock
 * {@link ConversationStateClient}, {@link LlmIntentClassifier} and the agent dispatch map exercise
 * the lock / no-lock / unknown-lock branches without an external service.
 */
class IntentRouterLockTest {

    private final LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
    private final ConversationStateClient conversationState = mock(ConversationStateClient.class);
    private final Agent calendar = mock(Agent.class);
    private final Agent finance = mock(Agent.class);
    private final Agent echo = mock(Agent.class);
    private final Map<String, Agent> agents = Map.of("calendar", calendar, "finance", finance, "echo", echo);

    private final IntentRouter router = new IntentRouter(agents, classifier, conversationState);

    private static NormalizedMessage msg() {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "да", java.util.List.of(), "telegram", "1", Instant.now());
    }

    private static ConversationStateDto lockedTo(String agent) {
        return new ConversationStateDto(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "telegram", agent, null, Instant.now().plusSeconds(600), Instant.now());
    }

    @Test
    void activeLockRoutesToLockedAgentBypassingClassifier() {
        when(conversationState.activeState(any(), any(), eq("telegram")))
                .thenReturn(Mono.just(lockedTo("calendar")));
        when(calendar.handle(any())).thenReturn(Mono.just(new IntentResponse("calendar", "ok", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> org.assertj.core.api.Assertions.assertThat(r.agent()).isEqualTo("calendar"))
                .verifyComplete();

        verify(calendar).handle(any());
        verify(classifier, never()).classify(any());
        verify(finance, never()).handle(any());
    }

    @Test
    void noLockClassifiesNormally() {
        when(conversationState.activeState(any(), any(), any())).thenReturn(Mono.empty());
        when(classifier.classify(any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> org.assertj.core.api.Assertions.assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        verify(classifier).classify(any());
        verify(finance).handle(any());
    }

    @Test
    void lockToUnknownAgentFallsThroughToClassifier() {
        when(conversationState.activeState(any(), any(), any()))
                .thenReturn(Mono.just(lockedTo("ghost-agent")));
        when(classifier.classify(any())).thenReturn(Mono.just("finance"));
        when(finance.handle(any())).thenReturn(Mono.just(new IntentResponse("finance", "ok", "m")));

        StepVerifier.create(router.route(msg()))
                .assertNext(r -> org.assertj.core.api.Assertions.assertThat(r.agent()).isEqualTo("finance"))
                .verifyComplete();

        verify(classifier).classify(any());
    }
}
