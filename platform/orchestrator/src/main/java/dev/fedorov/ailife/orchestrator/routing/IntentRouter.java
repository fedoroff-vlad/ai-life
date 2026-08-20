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
 *
 * <p><b>Misroute-repair (road-test #484):</b> after a fresh specialist reply with no open question, the
 * agent + the message text are recorded as {@code last_route} (short correction-window TTL). On the next
 * turn — when there is no active lock — that {@code last_route} is passed into the classifier as a
 * {@link LlmIntentClassifier.PriorRoute}, so a correction ("не то, я про задачи") re-classifies with the
 * prior route as context and routes to the corrected intent. A correction that lands on a different agent
 * is logged as a routing-quality signal. {@code routeLock} still wins — an open question resumes and
 * last-route is not consulted.
 *
 * <p><b>"Why did you do that" trace (road-test #485, Track G):</b> when the classifier returns the reserved
 * {@code explain} outcome (a "почему ты так сделал" meta-query, only offered while a prior route exists),
 * the router answers it via {@link ExplainResponder} from the remembered last-route instead of dispatching
 * to a domain agent — and does <em>not</em> overwrite last-route, so a later correction still repairs the
 * original route.
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);
    private static final String ECHO = "echo";

    private final Map<String, Agent> agents;
    private final LlmIntentClassifier classifier;
    private final ConversationStateClient conversationState;
    private final ExplainResponder explainResponder;

    public IntentRouter(@Qualifier("agentDispatch") Map<String, Agent> agents,
                        LlmIntentClassifier classifier,
                        ConversationStateClient conversationState,
                        ExplainResponder explainResponder) {
        this.agents = agents;
        this.classifier = classifier;
        this.conversationState = conversationState;
        this.explainResponder = explainResponder;
    }

    public Mono<IntentResponse> route(NormalizedMessage message) {
        return conversationState.activeState(
                        message.householdId(), message.userId(), message.sourceChannel())
                .flatMap(state -> {
                    if (state.routeLock() != null && agents.containsKey(state.routeLock())) {
                        return resumeLocked(message, state);
                    }
                    return classifyAndDispatch(message, priorRouteFrom(state));
                })
                .switchIfEmpty(Mono.defer(() -> classifyAndDispatch(message, null)));
    }

    /** A recorded last-route becomes the classifier's correction context; null when there is none. */
    private static LlmIntentClassifier.PriorRoute priorRouteFrom(ConversationStateDto state) {
        if (state.lastRouteAgent() == null || state.lastRouteAgent().isBlank()) {
            return null;
        }
        return new LlmIntentClassifier.PriorRoute(state.lastRouteAgent(), state.lastRouteText());
    }

    /** Locked reply → the owning agent's resume; then re-lock or clear based on what it returns. */
    private Mono<IntentResponse> resumeLocked(NormalizedMessage message, ConversationStateDto state) {
        String lock = state.routeLock();
        log.debug("route-lock active → resuming userId={} on agent={}", message.userId(), lock);
        return agents.get(lock).resume(new ResumeRequest(message, state.pendingAction()))
                .flatMap(resp -> applyLockLifecycle(message, resp, true));
    }

    private Mono<IntentResponse> classifyAndDispatch(NormalizedMessage message,
                                                     LlmIntentClassifier.PriorRoute priorRoute) {
        return classifier.classify(message, priorRoute)
                .flatMap(name -> {
                    // "Why did you do that" (Track G, #485): a meta-query about the prior route is answered
                    // from the remembered last-route, not dispatched. It leaves last_route untouched so a
                    // later correction still repairs the ORIGINAL route. If 'explain' ever surfaces without a
                    // prior route (it shouldn't — only offered inside the PriorRoute block), fall through to
                    // normal dispatch, where 'explain' is an unknown agent → echo.
                    if (ExplainResponder.EXPLAIN.equals(name) && priorRoute != null) {
                        log.debug("explain trace for userId={} prior_agent={}",
                                message.userId(), priorRoute.agent());
                        return explainResponder.explain(message, priorRoute);
                    }
                    Agent target = agents.getOrDefault(name, agents.get(ECHO));
                    log.debug("routed userId={} to agent={}", message.userId(), target.id());
                    logCorrection(priorRoute, target.id(), message);
                    return target.handle(message).flatMap(resp -> applyLockLifecycle(message, resp, false));
                });
    }

    /** A correction that steered to a different agent is a routing-quality signal (#484). */
    private static void logCorrection(LlmIntentClassifier.PriorRoute priorRoute, String chosen,
                                      NormalizedMessage message) {
        if (priorRoute != null && chosen != null && !chosen.equals(priorRoute.agent())
                && !ECHO.equals(chosen)) {
            log.info("routing-correction userId={} agent_from={} agent_to={} phrasing=\"{}\"",
                    message.userId(), priorRoute.agent(), chosen, message.text());
        }
    }

    /**
     * Persist the conversation state implied by the agent's reply: a non-null {@code pendingAction}
     * locks (or re-locks) to the replying agent; on a resume turn a null one clears the resolved lock;
     * a fresh specialist turn with no pendingAction records the {@code last_route} for misroute-repair
     * (#484). echo is never recorded — a correction after small talk is meaningless.
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
        if (resp.agent() != null && !ECHO.equals(resp.agent())) {
            return conversationState.recordLastRoute(message.householdId(), message.userId(),
                            message.sourceChannel(), resp.agent(), message.text())
                    .thenReturn(resp);
        }
        return Mono.just(resp);
    }
}
