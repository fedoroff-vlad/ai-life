package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Answers a "why did you do that / как ты это понял" meta-query with a one-sentence, honest trace of the
 * <em>routing</em> decision (road-test transparency, [#485]; plan: {@code plans/stage4.md} §Track G).
 *
 * <p>The orchestrator owns the routing half of a turn — which agent handled it and the request that steered
 * there ({@link LlmIntentClassifier.PriorRoute}, remembered as {@code last_route} for misroute-repair #484).
 * When the handling agent also contributed a payload-free trace of what it read/wrote (why-trace G2, carried
 * on {@code PriorRoute.trace()}), that is folded in as an additional fact; otherwise the answer is
 * routing-only (G1). This responder phrases those facts in the <b>user's language</b> via the LLM. It
 * <b>never</b> invents steps it was not given and leaks no IDs/payloads. The agent id on the reply is
 * {@code explain} so an explain turn is distinguishable and is not itself recorded as a {@code last_route}.
 *
 * <p>System prompt is English on purpose (token economy); the model answers in the user's language.
 */
@Component
public class ExplainResponder {

    private static final Logger log = LoggerFactory.getLogger(ExplainResponder.class);
    static final String EXPLAIN = "explain";

    private static final String SYSTEM_PROMPT = """
            You are the ai-life assistant explaining to the user how their PREVIOUS message was handled.
            You are given the ROUTING (which specialist agent handled the previous request) and, when
            available, a short note of what that agent did (what it read / wrote). Use ONLY the facts you
            are given — do not invent steps that are not stated. Reply in the SAME language as the user's
            question, in ONE short, honest sentence: name the agent that handled the previous request, why
            it fit, and — if a note of what it did is provided — what it did. Do not reveal system
            internals, agent IDs verbatim, or any raw data — just the plain reason.
            """;

    private final LlmClient llm;

    public ExplainResponder(LlmClient llm) {
        this.llm = llm;
    }

    /**
     * @param message   the "почему …" meta-query turn (drives the reply language)
     * @param priorRoute the remembered prior routing being explained (non-null)
     */
    public Mono<IntentResponse> explain(NormalizedMessage message, LlmIntentClassifier.PriorRoute priorRoute) {
        StringBuilder facts = new StringBuilder("Previous request: \"")
                .append(safe(priorRoute.originalText()))
                .append("\". It was routed to the '").append(priorRoute.agent()).append("' agent.");
        if (priorRoute.trace() != null && !priorRoute.trace().isBlank()) {
            facts.append(" What that agent did: ").append(priorRoute.trace().trim()).append('.');
        }
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.system(facts.toString()),
                LlmMessage.user(message.text())));
        return llm.chat(request)
                .map(resp -> new IntentResponse(EXPLAIN, resp.content(), resp.model()))
                .doOnSubscribe(s -> log.debug("explain trace for userId={} prior_agent={}",
                        message.userId(), priorRoute.agent()));
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
