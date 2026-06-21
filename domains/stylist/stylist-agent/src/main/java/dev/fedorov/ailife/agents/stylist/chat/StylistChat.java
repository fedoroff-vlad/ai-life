package dev.fedorov.ailife.agents.stylist.chat;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The scaffold's chat fallback (ST-b): until the catalogue / analyse / capsule flows land, the
 * stylist replies conversationally — one LLM turn with AGENT.md as the system prompt and the user's
 * text as the user turn. Replaced incrementally as the real flows arrive (ST-c routes garment
 * photos, ST-d "analyse me", ST-e capsule advice); the fallback stays for plain style questions.
 */
@Component
public class StylistChat {

    private static final Logger log = LoggerFactory.getLogger(StylistChat.class);

    private final LlmClient llm;
    private final AgentManifest manifest;

    public StylistChat(LlmClient llm, AgentManifest manifest) {
        this.llm = llm;
        this.manifest = manifest;
    }

    public Mono<IntentResponse> reply(NormalizedMessage message) {
        String text = message == null ? null : message.text();
        if (text == null || text.isBlank()) {
            return Mono.just(new IntentResponse(manifest.name(),
                    "Чем помочь со стилем? Могу собрать капсулу, разобрать ваш гардероб или цветотип.",
                    null));
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(text)));
        return llm.chat(request)
                .map(r -> new IntentResponse(manifest.name(), r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("stylist chat failed: {}", e.toString());
                    return Mono.just(new IntentResponse(manifest.name(),
                            "Не получилось ответить прямо сейчас. Попробуйте позже.", null));
                });
    }
}
