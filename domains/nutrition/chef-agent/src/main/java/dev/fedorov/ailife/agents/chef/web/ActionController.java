package dev.fedorov.ailife.agents.chef.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.web.AgentActionController;
import dev.fedorov.ailife.agents.chef.flow.RecipeFinder;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Inter-agent action endpoint (CH-b2). The orchestrator forwards an {@link AgentActionRequest} here
 * when another agent invokes the chef — specifically the nutritionist's ration flow (NU-g) asking for
 * recipes for a planned ration (ration → recipes, the gift-recommender→finance shape). The only
 * action is {@code recommend_recipes}: it reads a free-text {@code args.request} (a ration / dish /
 * ingredients), runs the shared {@link RecipeFinder#recommend} core (web recipe search → synthesis →
 * HTML recipe card), and returns {@code {link, summary}} so the caller can hand the user the card.
 *
 * <p>Always replies with an {@link AgentActionResult} (never an HTTP error), so the caller gets a
 * structured {@code ok=false} on a bad request and can soft-fail.
 */
@RestController
public class ActionController extends AgentActionController {

    private final RecipeFinder recipeFinder;
    private final ObjectMapper json;

    public ActionController(RecipeFinder recipeFinder, ObjectMapper json) {
        super("chef");
        this.recipeFinder = recipeFinder;
        this.json = json;
        register("recommend_recipes", this::recommendRecipes);
    }

    @PostMapping("/agents/chef/actions/{action}")
    public Mono<AgentActionResult> action(@PathVariable String action,
                                          @RequestBody AgentActionRequest request) {
        return dispatch(action, request);
    }

    private Mono<AgentActionResult> recommendRecipes(AgentActionRequest request) {
        JsonNode args = request.args();
        String text = args != null && args.hasNonNull("request") ? args.get("request").asText() : null;
        if (text == null || text.isBlank()) {
            return Mono.just(AgentActionResult.error("recommend_recipes requires args.request"));
        }
        return recipeFinder.recommend(request.householdId(), request.userId(), text)
                .map(o -> {
                    var result = json.createObjectNode();
                    if (o.link() != null) result.put("link", o.link());
                    if (o.summary() != null) result.put("summary", o.summary());
                    return AgentActionResult.ok(result);
                });
    }
}
