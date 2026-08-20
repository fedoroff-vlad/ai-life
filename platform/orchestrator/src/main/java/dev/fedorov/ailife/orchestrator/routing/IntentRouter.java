package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import dev.fedorov.ailife.orchestrator.agent.Agent;
import dev.fedorov.ailife.orchestrator.conversation.ConversationStateClient;
import tools.jackson.databind.JsonNode;
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
 *
 * <p><b>"Отмени последнее" undo (road-test #486, Track H):</b> an agent's terminal write can carry an
 * {@link dev.fedorov.ailife.contracts.agent.UndoHandle} on its reply; the router remembers it as the
 * conversation's {@code last_mutation} (carried forward across a plain read turn). When the classifier
 * returns the reserved {@code undo} outcome (offered only while a {@code last_mutation} exists), the router
 * reverses it by dispatching the stored handle to the recording agent's {@code /actions/undo} via the C1
 * {@code invoke} primitive, surfaces that agent's confirmation (or its honest "нельзя отменить"), and clears
 * only the consumed mutation — last-route is preserved. Nothing to undo → the classifier never offers
 * {@code undo}, so "отмени последнее" classifies normally instead of silently no-op'ing.
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);
    private static final String ECHO = "echo";
    /** Reserved routing outcome for "отмени последнее" (Track H): reversed by the router, not dispatched. */
    static final String UNDO = "undo";

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
                    return classifyAndDispatch(message, priorRouteFrom(state), undoableFrom(state), state);
                })
                .switchIfEmpty(Mono.defer(() -> classifyAndDispatch(message, null, null, null)));
    }

    /** A recorded last-route becomes the classifier's correction context; null when there is none. */
    private static LlmIntentClassifier.PriorRoute priorRouteFrom(ConversationStateDto state) {
        if (state.lastRouteAgent() == null || state.lastRouteAgent().isBlank()) {
            return null;
        }
        return new LlmIntentClassifier.PriorRoute(
                state.lastRouteAgent(), state.lastRouteText(), state.lastRouteTrace());
    }

    /** A recorded last-mutation offers the classifier the reserved {@code undo} outcome; null when none. */
    private static LlmIntentClassifier.Undoable undoableFrom(ConversationStateDto state) {
        if (state.lastMutationAgent() == null || state.lastMutationAgent().isBlank()) {
            return null;
        }
        return new LlmIntentClassifier.Undoable(state.lastMutationDesc());
    }

    /** Locked reply → the owning agent's resume; then re-lock or clear based on what it returns. */
    private Mono<IntentResponse> resumeLocked(NormalizedMessage message, ConversationStateDto state) {
        String lock = state.routeLock();
        log.debug("route-lock active → resuming userId={} on agent={}", message.userId(), lock);
        return agents.get(lock).resume(new ResumeRequest(message, state.pendingAction()))
                .flatMap(resp -> applyLockLifecycle(message, resp, true, state));
    }

    private Mono<IntentResponse> classifyAndDispatch(NormalizedMessage message,
                                                     LlmIntentClassifier.PriorRoute priorRoute,
                                                     LlmIntentClassifier.Undoable undoable,
                                                     ConversationStateDto state) {
        return classifier.classify(message, priorRoute, undoable)
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
                    // "Отмени последнее" (Track H, #486): reverse the remembered last_mutation via the
                    // recording agent's /actions/undo, not dispatched to a fresh intent. Only reachable when
                    // an undoable mutation exists (state non-null); a stray 'undo' otherwise falls through to
                    // echo.
                    if (UNDO.equals(name) && undoable != null && state != null) {
                        return undoLastMutation(message, state);
                    }
                    Agent target = agents.getOrDefault(name, agents.get(ECHO));
                    log.debug("routed userId={} to agent={}", message.userId(), target.id());
                    logCorrection(priorRoute, target.id(), message);
                    return target.handle(message)
                            .flatMap(resp -> applyLockLifecycle(message, resp, false, state));
                });
    }

    /**
     * Reverse the conversation's remembered last-mutation (Track H): dispatch the stored opaque handle to
     * the recording agent's {@code /actions/undo} (via the C1 {@code invoke} primitive) and surface its
     * confirmation. On success the mutation is consumed (cleared) while last-route is preserved; on an honest
     * "can't undo" the mutation is left in place so the owner can retry. Never a silent no-op.
     */
    private Mono<IntentResponse> undoLastMutation(NormalizedMessage message, ConversationStateDto state) {
        String agentName = state.lastMutationAgent();
        Agent target = agentName == null ? null : agents.get(agentName);
        if (target == null) {
            log.warn("undo requested but recording agent unavailable userId={} agent={}",
                    message.userId(), agentName);
            return Mono.just(new IntentResponse(UNDO,
                    "Не получилось отменить: агент, выполнивший действие, сейчас недоступен.", null));
        }
        var req = new AgentActionRequest(agentName, UNDO, message.householdId(), message.userId(),
                "orchestrator", state.lastMutationPayload());
        log.debug("undo last_mutation userId={} agent={}", message.userId(), agentName);
        return target.invoke(req)
                .flatMap(result -> {
                    if (result != null && result.ok()) {
                        return clearMutationKeepRoute(message, state)
                                .thenReturn(new IntentResponse(UNDO,
                                        undoConfirmation(result, state.lastMutationDesc()), null));
                    }
                    String err = (result != null && result.error() != null && !result.error().isBlank())
                            ? result.error() : "Это действие нельзя отменить.";
                    return Mono.just(new IntentResponse(UNDO, err, null));
                })
                .onErrorResume(e -> {
                    log.warn("undo dispatch failed userId={} agent={}: {}",
                            message.userId(), agentName, e.toString());
                    return Mono.just(new IntentResponse(UNDO,
                            "Не получилось отменить последнее действие.", null));
                });
    }

    /** Prefer the agent's own user-facing confirmation; else a generic one naming what was undone. */
    private static String undoConfirmation(AgentActionResult result, String description) {
        JsonNode r = result.result();
        if (r != null) {
            String msg = r.path("message").asString();
            if (msg != null && !msg.isBlank()) {
                return msg;
            }
        }
        return (description != null && !description.isBlank())
                ? "Отменил: " + description + "." : "Готово, отменил последнее действие.";
    }

    /** Consume the undone mutation but keep last-route (so a later correction still repairs it). */
    private Mono<Void> clearMutationKeepRoute(NormalizedMessage message, ConversationStateDto state) {
        if (state.lastRouteAgent() == null || state.lastRouteAgent().isBlank()) {
            return conversationState.clear(message.householdId(), message.userId(), message.sourceChannel());
        }
        return conversationState.recordLastRoute(message.householdId(), message.userId(),
                message.sourceChannel(), state.lastRouteAgent(), state.lastRouteText(),
                state.lastRouteTrace(), null, null, null);
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
     * (#484) plus the {@code last_mutation} for undo (#486). echo is never recorded — a correction after
     * small talk is meaningless.
     *
     * <p><b>Undo (Track H):</b> a fresh terminal write carries its own {@link IntentResponse#undo()} handle
     * as the new {@code last_mutation}; a fresh turn that mutated nothing (no handle) <em>carries forward</em>
     * the prior mutation from {@code priorState}, so a plain read turn between a write and "отмени последнее"
     * doesn't erase what's undoable. The two groups are written together (one clobber-all upsert).
     */
    private Mono<IntentResponse> applyLockLifecycle(NormalizedMessage message, IntentResponse resp,
                                                    boolean cameFromResume, ConversationStateDto priorState) {
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
            String mutAgent;
            JsonNode mutPayload;
            String mutDesc;
            if (resp.undo() != null) {                 // this turn wrote something reversible
                mutAgent = resp.agent();
                mutPayload = resp.undo().action();
                mutDesc = resp.undo().description();
            } else if (priorState != null) {           // carry an unconsumed prior mutation across a read turn
                mutAgent = priorState.lastMutationAgent();
                mutPayload = priorState.lastMutationPayload();
                mutDesc = priorState.lastMutationDesc();
            } else {
                mutAgent = null;
                mutPayload = null;
                mutDesc = null;
            }
            return conversationState.recordLastRoute(message.householdId(), message.userId(),
                            message.sourceChannel(), resp.agent(), message.text(), resp.trace(),
                            mutAgent, mutPayload, mutDesc)
                    .thenReturn(resp);
        }
        return Mono.just(resp);
    }
}
