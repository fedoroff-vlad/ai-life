package dev.fedorov.ailife.agents.chef.web;

import dev.fedorov.ailife.agents.chef.chat.ChefChat;
import dev.fedorov.ailife.agents.chef.flow.RecipeFinder;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

/**
 * Hit by the orchestrator when intent routing selects {@code chef}. CH-b: a recipe-cue message
 * ("рецепт", "что приготовить", "как приготовить") → {@link RecipeFinder#findRecipes} (web recipe
 * search → recipe card → HTML → link); otherwise the {@link ChefChat} fallback. The nutritionist's
 * ration flow (NU-g) invokes the chef over the orchestrator hub (ration → recipes) — wired in CH-b2.
 *
 * <p>The cue split is a deterministic keyword heuristic — good enough for the MVP,
 * MockWebServer-testable, and replaceable by an LLM classifier later.
 */
@RestController
@RequestMapping("/agents/chef")
public class IntentController {

    private static final Set<String> RECIPE_CUES = Set.of(
            "рецепт", "что приготовить", "как приготовить", "как готовить", "приготовь",
            "что сготовить", "что сделать из", "блюдо из", "что можно приготовить",
            "recipe", "recipes", "how to cook", "what to cook", "cook ", "what can i make");

    private final RecipeFinder recipeFinder;
    private final ChefChat chat;

    public IntentController(RecipeFinder recipeFinder, ChefChat chat) {
        this.recipeFinder = recipeFinder;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        if (isMatch(message.text(), RECIPE_CUES)) {
            return recipeFinder.findRecipes(message);
        }
        return chat.reply(message);
    }

    private static boolean isMatch(String text, Set<String> cues) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return cues.stream().anyMatch(t::contains);
    }
}
