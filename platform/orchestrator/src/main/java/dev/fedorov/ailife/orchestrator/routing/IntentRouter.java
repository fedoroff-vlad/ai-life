package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.orchestrator.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Routes a {@link NormalizedMessage} to an agent picked by
 * {@link LlmIntentClassifier}. The dispatch map is built in
 * {@link dev.fedorov.ailife.orchestrator.agent.AgentDiscovery} (echo + remotes
 * whose manifest was reachable at startup). Echo is always present and is the
 * fallback when classification fails or selects an unknown name.
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private final Map<String, Agent> agents;
    private final LlmIntentClassifier classifier;

    public IntentRouter(@Qualifier("agentDispatch") Map<String, Agent> agents,
                        LlmIntentClassifier classifier) {
        this.agents = agents;
        this.classifier = classifier;
    }

    public Mono<IntentResponse> route(NormalizedMessage message) {
        return classifier.classify(message)
                .flatMap(name -> {
                    Agent target = agents.getOrDefault(name, agents.get("echo"));
                    log.debug("routed userId={} to agent={}", message.userId(), target.id());
                    return target.handle(message);
                });
    }
}
