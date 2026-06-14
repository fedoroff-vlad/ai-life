package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import dev.fedorov.ailife.orchestrator.agent.Agent;
import dev.fedorov.ailife.orchestrator.conversation.ConversationStateClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Routes a {@link NormalizedMessage} to an agent and manages the conversation route-lock lifecycle
 * (Stage 4 / Track A).
 *
 * <p><b>Route-lock first:</b> if conversation-service holds an active route-lock for this
 * (household, user, channel) naming a known agent, the message is a reply to that agent's open
 * question — it's sent to that agent's {@code resume} with the stored {@code pendingAction},
 * <em>bypassing</em> classification. Otherwise (no lock / unknown-agent lock / conversation-service
 * unreachable — the client soft-fails to empty) the message is classified and dispatched.
 *
 * <p><b>Lock lifecycle after the agent replies:</b> if the {@link IntentResponse} carries a
 * {@code pendingAction} the agent is awaiting a reply → lock the conversation to it; on a resume turn
 * that came back <em>without</em> a pendingAction the question is resolved → clear the lock. Lock
 * writes/clears are soft-fail (a confirmation that can't be persisted just won't survive — never a
 * user-facing error). The dispatch map is built in
 * {@link dev.fedorov.ailife.orchestrator.agent.AgentDiscovery}; echo is the classification fallback.
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private final Map<String, Agent> agents;
    private final LlmIntentClassifier classifier;
    private final ConversationStateClient conversationState;

    public IntentRouter(@Qualifier("agentDispatch") Map<String, Agent> agents,
                        LlmIntentClassifier classifier,
                        ConversationStateClient conversationState) {
        this.agents = agents;
        this.classifier = classifier;
        this.conversationState = conversationState;
    }

    public Mono<IntentResponse> route(NormalizedMessage message) {
        return conversationState.activeState(
                        message.householdId(), message.userId(), message.sourceChannel())
                .filter(state -> state.routeLock() != null && agents.containsKey(state.routeLock()))
                .flatMap(state -> resumeLocked(message, state))
                .switchIfEmpty(Mono.defer(() -> classifyAndDispatch(message)));
    }

    /** Locked reply → the owning agent's resume; then re-lock or clear based on what it returns. */
    private Mono<IntentResponse> resumeLocked(NormalizedMessage message, ConversationStateDto state) {
        String lock = state.routeLock();
        log.debug("route-lock active → resuming userId={} on agent={}", message.userId(), lock);
        return agents.get(lock).resume(new ResumeRequest(message, state.pendingAction()))
                .flatMap(resp -> applyLockLifecycle(message, resp, true));
    }

    private Mono<IntentResponse> classifyAndDispatch(NormalizedMessage message) {
        return classifier.classify(message)
                .flatMap(name -> {
                    Agent target = agents.getOrDefault(name, agents.get("echo"));
                    log.debug("routed userId={} to agent={}", message.userId(), target.id());
                    return target.handle(message).flatMap(resp -> applyLockLifecycle(message, resp, false));
                });
    }

    /**
     * Persist the conversation lock implied by the agent's reply: a non-null {@code pendingAction}
     * locks (or re-locks) to the replying agent; on a resume turn a null one clears the resolved
     * lock. A fresh turn with no pendingAction needs no write (there was no lock).
     */
    private Mono<IntentResponse> applyLockLifecycle(NormalizedMessage message, IntentResponse resp,
                                                    boolean cameFromResume) {
        if (resp.pendingAction() != null) {
            return conversationState.lock(message.householdId(), message.userId(),
                            message.sourceChannel(), resp.agent(), resp.pendingAction())
                    .thenReturn(resp);
        }
        if (cameFromResume) {
            return conversationState.clear(message.householdId(), message.userId(),
                            message.sourceChannel())
                    .thenReturn(resp);
        }
        return Mono.just(resp);
    }
}
