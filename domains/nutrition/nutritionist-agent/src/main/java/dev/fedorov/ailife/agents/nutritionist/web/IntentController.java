package dev.fedorov.ailife.agents.nutritionist.web;

import dev.fedorov.ailife.agents.nutritionist.chat.NutritionistChat;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code nutritionist}. The NU-b scaffold ships
 * only the {@link NutritionistChat} fallback — a meal photo / receipt routes to the food-log and
 * basket-breakdown flows once they land (NU-c / NU-f), and ration/shopping-list cues to NU-g; until
 * then every routed message replies conversationally.
 */
@RestController
@RequestMapping("/agents/nutritionist")
public class IntentController {

    private final NutritionistChat chat;

    public IntentController(NutritionistChat chat) {
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return chat.reply(message);
    }
}
