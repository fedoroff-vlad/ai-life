package dev.fedorov.ailife.agents.travel.chat;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
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
 * The scaffold's chat fallback (TR-c): for an open-ended message that isn't a preferences update, the
 * travel agent replies conversationally — one LLM turn with AGENT.md as the system prompt and the
 * user's text as the user turn. The trip-planner flow (TR-d) will handle explicit "plan me a trip"
 * requests; this fallback stays for plain questions.
 */
@Component
public class TravelChat {

    private static final Logger log = LoggerFactory.getLogger(TravelChat.class);

    private final LlmClient llm;
    private final AgentManifest manifest;

    public TravelChat(LlmClient llm, AgentManifest manifest) {
        this.llm = llm;
        this.manifest = manifest;
    }

    public Mono<IntentResponse> reply(NormalizedMessage message) {
        String text = message == null ? null : message.text();
        if (text == null || text.isBlank()) {
            return Mono.just(new IntentResponse(manifest.name(),
                    "Чем помочь с путешествием? Могу сохранить ваши предпочтения (город вылета, тип отдыха, с кем, бюджет) или спланировать поездку.",
                    null));
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(text)));
        return llm.chat(request)
                .map(r -> new IntentResponse(manifest.name(), r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("travel chat failed: {}", e.toString());
                    return Mono.just(new IntentResponse(manifest.name(),
                            "Не получилось ответить прямо сейчас. Попробуйте позже.", null));
                });
    }
}
