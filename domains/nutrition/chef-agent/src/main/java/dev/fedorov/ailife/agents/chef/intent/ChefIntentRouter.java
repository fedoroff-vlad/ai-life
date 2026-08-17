package dev.fedorov.ailife.agents.chef.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.chef.chat.ChefChat;
import dev.fedorov.ailife.agents.chef.flow.RecipeFinder;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Routes a <b>text</b> message the orchestrator sent to {@code chef} into its one text-intent flow — find
 * recipes for a dish / ingredients / a ration ({@link RecipeFinder#findRecipes}) — or a plain chat reply
 * ({@link ChefChat}). Chef has a single routable intent skill ({@code recipe-finder}), but the recipe-vs-chat
 * decision is a real one, so it is the shared {@link SkillClassifier}'s to make from the SKILL.md description
 * rather than a keyword list's: the old {@code RECIPE_CUES} heuristic misrouted any paraphrase outside its
 * fixed list ("чем бы поужинать из курицы"), the exact weakness #475 removes. The nutritionist's ration flow
 * invokes the chef over the orchestrator hub (a {@code recommend_recipes} action, not an intent), so it never
 * reaches this router.
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, skills-vs-flows
 * Bucket 1 / #475): it supplies the chef-specific parts — the ordered {@code {skillName → flow}} dispatch map
 * ({@code recipe-finder}), the {@link ChefChat} fallback, and the intro/decide framing — and the shared
 * router owns the LLM round-trip, the {@link SkillClassifier} parse, and the soft-fail-to-chat dispatch.
 * Chef binds no directly-routable MCP tools; {@code recipe-finder} is loaded from the shared
 * {@code skills/nutrition/*} classpath (chef's own skill, unlike its nutritionist siblings there).
 */
@Component
public class ChefIntentRouter {

    private static final String RECIPE_FINDER = "recipe-finder";

    private final SkillRouter router;

    public ChefIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                            AgentManifest manifest, RecipeFinder recipeFinder, ChefChat chat) {
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(RECIPE_FINDER, recipeFinder::findRecipes);
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the chef agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (find recipes for a dish, ingredients, or a ration) "
                        + "or just talk?",
                flows, chat::reply);
    }

    public Mono<IntentResponse> route(NormalizedMessage msg) {
        return router.route(msg);
    }

    /** The exact classifier prompt {@link #route} builds — replayed by the routing golden. */
    String buildClassifierPrompt() {
        return router.buildClassifierPrompt();
    }
}
