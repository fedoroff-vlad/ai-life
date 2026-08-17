package dev.fedorov.ailife.agents.chef.web;

import dev.fedorov.ailife.agents.chef.intent.ChefIntentRouter;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code chef}: delegates to {@link ChefIntentRouter},
 * which classifies the message (via the shared {@code SkillClassifier}) into the one text-intent flow —
 * find recipes ({@code recipe-finder}) — or a plain chat reply.
 *
 * <p>The deterministic {@code RECIPE_CUES} keyword heuristic this controller used to carry was replaced by
 * the LLM classifier in skills-vs-flows Bucket 1 (#475), so the routing SSOT is the {@code recipe-finder}
 * SKILL.md description. The nutritionist's ration flow (NU-g) invokes the chef over the orchestrator hub
 * (a {@code recommend_recipes} action on {@code /agents/chef/actions/*}, not an intent), so it never reaches
 * this controller.
 */
@RestController
@RequestMapping("/agents/chef")
public class IntentController {

    private final ChefIntentRouter router;

    public IntentController(ChefIntentRouter router) {
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return router.route(message);
    }
}
