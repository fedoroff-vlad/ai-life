package dev.fedorov.ailife.agents.chef.web;

import dev.fedorov.ailife.agents.chef.chat.ChefChat;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code chef}. CH-a ships the chat fallback
 * only; the recipe flow (CH-b) replaces this branch — a recipe request → web recipe search → an HTML
 * recipe card. The nutritionist's ration flow (NU-g) invokes the chef over the orchestrator hub
 * (ration → recipes), wired in CH-b as well.
 */
@RestController
@RequestMapping("/agents/chef")
public class IntentController {

    private final ChefChat chat;

    public IntentController(ChefChat chat) {
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return chat.reply(message);
    }
}
