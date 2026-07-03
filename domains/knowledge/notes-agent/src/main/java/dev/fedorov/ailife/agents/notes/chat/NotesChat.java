package dev.fedorov.ailife.agents.notes.chat;

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
 * The chat fallback for a message that is neither a "запомни …" capture nor a "что я думал про …"
 * recall cue: the notes agent replies conversationally with AGENT.md as the system prompt, pointing the
 * user at what it can do (save a note, or recall one).
 */
@Component
public class NotesChat {

    private static final Logger log = LoggerFactory.getLogger(NotesChat.class);

    private final LlmClient llm;
    private final AgentManifest manifest;

    public NotesChat(LlmClient llm, AgentManifest manifest) {
        this.llm = llm;
        this.manifest = manifest;
    }

    public Mono<IntentResponse> reply(NormalizedMessage message) {
        String text = message == null ? null : message.text();
        if (text == null || text.isBlank()) {
            return Mono.just(new IntentResponse(manifest.name(),
                    "Скажите «запомни, что …», чтобы сохранить заметку, или «что я думал про …», чтобы найти её.",
                    null));
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(text)));
        return llm.chat(request)
                .map(r -> new IntentResponse(manifest.name(), r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("notes chat failed: {}", e.toString());
                    return Mono.just(new IntentResponse(manifest.name(),
                            "Не получилось ответить прямо сейчас. Попробуйте позже.", null));
                });
    }
}
