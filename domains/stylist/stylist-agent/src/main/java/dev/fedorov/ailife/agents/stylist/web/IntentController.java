package dev.fedorov.ailife.agents.stylist.web;

import dev.fedorov.ailife.agents.stylist.chat.StylistChat;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code stylist}. ST-b ships a chat fallback
 * (see {@link StylistChat}); the catalogue / analyse / capsule flows replace branches of this as
 * they land (ST-c..e).
 */
@RestController
@RequestMapping("/agents/stylist")
public class IntentController {

    private final StylistChat chat;

    public IntentController(StylistChat chat) {
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return chat.reply(message);
    }
}
