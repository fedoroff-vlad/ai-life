package dev.fedorov.ailife.agents.creator.web;

import dev.fedorov.ailife.agents.creator.chat.CreatorChat;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code creator}. CR-b ships only the
 * {@link CreatorChat} fallback (one LLM turn, AGENT.md as the system prompt); the real flows replace
 * branches incrementally — CR-c (creator profile), CR-d (trend → ideas → drafts synthesis).
 */
@RestController
@RequestMapping("/agents/creator")
public class IntentController {

    private final CreatorChat chat;

    public IntentController(CreatorChat chat) {
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return chat.reply(message);
    }
}
