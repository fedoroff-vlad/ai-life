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
 * <p>The orchestrator genuinely owns only the routing half of a turn — which agent handled it and the
 * request that steered there ({@link LlmIntentClassifier.PriorRoute}, remembered as {@code last_route} for
 * misroute-repair #484). This responder phrases that fact in the <b>user's language</b> via the LLM. It
 * <b>never</b> invents the agent's internal steps (what it read/wrote — that is the follow-on G2 slice) and
 * leaks no IDs/payloads. The agent id on the reply is {@code explain} so an explain turn is distinguishable
 * and is not itself recorded as a {@code last_route}.
 *
 * <p>System prompt is English on purpose (token economy); the model answers in the user's language.
 */
@Component
public class ExplainResponder {

    private static final Logger log = LoggerFactory.getLogger(ExplainResponder.class);
    static final String EXPLAIN = "explain";

    private static final String SYSTEM_PROMPT = """
            You are the ai-life assistant explaining to the user how their PREVIOUS message was handled.
            You know only the ROUTING: the previous request was sent to a specialist agent for that domain.
            You do NOT know the agent's internal steps (what it read or wrote), so do not invent any.
            Reply in the SAME language as the user's question, in ONE short, honest sentence: name the
            agent that handled the previous request and why it fit. Do not reveal system internals, agent
            IDs verbatim, or any raw data — just the plain reason.
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
        String facts = "Previous request: \"" + safe(priorRoute.originalText())
                + "\". It was routed to the '" + priorRoute.agent() + "' agent.";
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.system(facts),
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
