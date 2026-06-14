package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
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
 * Routes a {@link NormalizedMessage} to an agent.
 *
 * <p><b>Route-lock first (Stage 4 / A2):</b> if conversation-service holds an active route-lock for
 * this (household, user, channel) naming a known agent, the message is a reply to that agent's open
 * question — it's routed straight there, <em>bypassing</em> intent classification. Otherwise (no
 * lock, lock to an unknown agent, or conversation-service unreachable — the client soft-fails to
 * empty) the message is classified by {@link LlmIntentClassifier} and dispatched. The dispatch map
 * is built in {@link dev.fedorov.ailife.orchestrator.agent.AgentDiscovery} (echo + remotes reachable
 * at startup); echo is the fallback when classification fails or selects an unknown name.
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
                .map(ConversationStateDto::routeLock)
                .filter(lock -> lock != null && agents.containsKey(lock))
                .flatMap(lock -> {
                    log.debug("conversation route-lock active → routing userId={} directly to agent={}",
                            message.userId(), lock);
                    return agents.get(lock).handle(message);
                })
                .switchIfEmpty(Mono.defer(() -> classifyAndDispatch(message)));
    }

    private Mono<IntentResponse> classifyAndDispatch(NormalizedMessage message) {
        return classifier.classify(message)
                .flatMap(name -> {
                    Agent target = agents.getOrDefault(name, agents.get("echo"));
                    log.debug("routed userId={} to agent={}", message.userId(), target.id());
                    return target.handle(message);
                });
    }
}
