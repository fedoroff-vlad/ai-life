package dev.fedorov.ailife.orchestrator.agent;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Default Stage 0 agent. Proves the full pipe works: gateway → orchestrator →
 * llm-gateway → back. Replaced by intent classification + real agents in Stage 1+.
 *
 * <p>System prompt is in English on purpose (token economy, see plan §6).
 */
@Component
public class EchoAgent implements Agent {

    private static final String SYSTEM_PROMPT = """
            You are the Stage-0 echo agent for the ai-life system. Reply briefly to the
            user's message in the same language they used. Keep responses under two
            sentences. This agent is a placeholder until real domain agents come online.
            """;

    private final LlmClient llm;

    public EchoAgent(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public String id() {
        return "echo";
    }

    @Override
    public Mono<IntentResponse> handle(NormalizedMessage message) {
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(SYSTEM_PROMPT),
                LlmMessage.user(message.text())));

        return llm.chat(request)
                .map(resp -> new IntentResponse(id(), resp.content(), resp.model()));
    }
}
