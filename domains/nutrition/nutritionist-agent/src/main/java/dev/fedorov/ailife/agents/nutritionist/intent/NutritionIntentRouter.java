package dev.fedorov.ailife.agents.nutritionist.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.nutritionist.analysis.NutritionAnalyst;
import dev.fedorov.ailife.agents.nutritionist.basket.BasketBreakdown;
import dev.fedorov.ailife.agents.nutritionist.chat.NutritionistChat;
import dev.fedorov.ailife.agents.nutritionist.flow.MealPlanner;
import dev.fedorov.ailife.agents.nutritionist.foodlog.FoodLogger;
import dev.fedorov.ailife.agents.nutritionist.profile.DietProfiler;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Routes a <b>text</b> message the orchestrator sent to {@code nutritionist} into one of the agent's five
 * intent flows — set the diet profile ({@link DietProfiler}), analyse nutrition ({@link NutritionAnalyst}),
 * plan a ration ({@link MealPlanner}), break down a grocery basket ({@link BasketBreakdown#breakdownText}),
 * or log a meal ({@link FoodLogger#logText}) — or a plain chat reply ({@link NutritionistChat}). The
 * photo-ingest branches (basket photo / meal photo) stay a deterministic pre-check in
 * {@code IntentController} (an image attachment is unambiguously an ingest, not a text intent), so they
 * never reach this router.
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, skills-vs-flows
 * Bucket 1 / #475): it supplies the nutrition-specific parts — the ordered {@code {skillName → flow}}
 * dispatch map (`diet-profiler`/`nutrition-analyst`/`meal-planner`/`basket-analyst`/`meal-logger`), the
 * {@link NutritionistChat} fallback, and the intro/decide framing — and the shared router owns the LLM
 * round-trip, the {@link SkillClassifier} parse, and the soft-fail-to-chat dispatch. Each skill's SKILL.md
 * {@code description} is the routing SSOT, so a paraphrase outside the old {@code *_CUES} keyword lists
 * routes correctly. Where the deterministic cues checked the ration cue before the basket cue (so a
 * "stock up" planning request beat basket breakdown), the LLM now disambiguates from the descriptions.
 *
 * <p>{@code recipe-finder} is loaded from the shared {@code skills/nutrition/*} classpath but is
 * <b>chef-agent's</b> skill, not a nutritionist intent, so leaving it out of the map excludes it from both
 * the advertised route set and dispatch (the router keys its route set off the map). Nutritionist binds no
 * directly-routable MCP tools.
 *
 * <p>The <b>family/own scope</b> for the ration flow stays a deterministic keyword heuristic
 * ({@code FAMILY_CUES}) applied <i>inside</i> the {@code meal-planner} dispatch lambda — it is a
 * read-breadth choice (personal ∪ shared households, ADR-0002 slice 6b), never a routing or privacy-write
 * decision, so it is deliberately not handed to the LLM classifier.
 */
@Component
public class NutritionIntentRouter {

    private static final String DIET_PROFILER = "diet-profiler";
    private static final String NUTRITION_ANALYST = "nutrition-analyst";
    private static final String MEAL_PLANNER = "meal-planner";
    private static final String BASKET_ANALYST = "basket-analyst";
    private static final String MEAL_LOGGER = "meal-logger";

    /**
     * Family-scope cues (ADR-0002 slice 6b): when a ration request carries one of these, the ration reads
     * across the member's personal ∪ shared households, not just their own. Absent → the default (own) cut,
     * mirroring finance/tasks (shared is on explicit request, never by default). A read-breadth choice,
     * never a routing/privacy-write decision → kept off the LLM classifier.
     */
    private static final Set<String> FAMILY_CUES = Set.of(
            "на всю семью", "на семью", "для семьи", "для всей семьи", "наш рацион", "наше меню",
            "семейный рацион", "семейное меню", "семейный план", "нам всем", "на нас всех", "на всех нас",
            "for the family", "for all of us", "family meal plan", "whole family", "our ration",
            "our meal plan", "family ration");

    private final SkillRouter router;

    public NutritionIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                                 AgentManifest manifest, FoodLogger foodLogger, DietProfiler dietProfiler,
                                 NutritionAnalyst nutritionAnalyst, MealPlanner mealPlanner,
                                 BasketBreakdown basketBreakdown, NutritionistChat chat) {
        // recipe-finder (chef's skill, loaded from the shared nutrition classpath) is deliberately NOT mapped.
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(DIET_PROFILER, dietProfiler::setProfile);
        flows.put(NUTRITION_ANALYST, nutritionAnalyst::analyse);
        flows.put(MEAL_PLANNER, msg -> mealPlanner.plan(msg, isFamilyCut(msg.text())));
        flows.put(BASKET_ANALYST, basketBreakdown::breakdownText);
        flows.put(MEAL_LOGGER, foodLogger::logText);
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the nutritionist agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (set their diet profile / analyse their nutrition / "
                        + "plan a ration / break down groceries / log a meal) or just talk?",
                flows, chat::reply);
    }

    public Mono<IntentResponse> route(NormalizedMessage msg) {
        return router.route(msg);
    }

    private static boolean isFamilyCut(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return FAMILY_CUES.stream().anyMatch(t::contains);
    }

    /** The exact classifier prompt {@link #route} builds — replayed by the routing golden. */
    String buildClassifierPrompt() {
        return router.buildClassifierPrompt();
    }
}
