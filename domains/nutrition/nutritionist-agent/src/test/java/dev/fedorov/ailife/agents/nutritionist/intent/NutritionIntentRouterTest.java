package dev.fedorov.ailife.agents.nutritionist.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.nutritionist.analysis.NutritionAnalyst;
import dev.fedorov.ailife.agents.nutritionist.basket.BasketBreakdown;
import dev.fedorov.ailife.agents.nutritionist.chat.NutritionistChat;
import dev.fedorov.ailife.agents.nutritionist.flow.MealPlanner;
import dev.fedorov.ailife.agents.nutritionist.foodlog.FoodLogger;
import dev.fedorov.ailife.agents.nutritionist.profile.DietProfiler;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NutritionIntentRouter} — a mock {@link LlmClient} drives each classifier branch
 * and the flows are mocked, so we assert exactly which flow the router dispatched to (the parity check for
 * the cue→classifier migration, #475). Mirrors notes/creator/docs router tests, plus the nutrition-specific
 * FAMILY_CUES ration read-scope flag (still deterministic, applied inside the meal-planner dispatch lambda)
 * and the recipe-finder exclusion (chef's skill, loaded from the shared classpath, never a nutritionist
 * intent).
 */
class NutritionIntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final FoodLogger foodLogger = mock(FoodLogger.class);
    private final DietProfiler dietProfiler = mock(DietProfiler.class);
    private final NutritionAnalyst nutritionAnalyst = mock(NutritionAnalyst.class);
    private final MealPlanner mealPlanner = mock(MealPlanner.class);
    private final BasketBreakdown basketBreakdown = mock(BasketBreakdown.class);
    private final NutritionistChat chat = mock(NutritionistChat.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final AgentManifest manifest = new AgentManifest(
            "nutritionist", "test", "0.0.1", 0, List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the nutritionist agent for the ai-life system.");
    // recipe-finder is loaded (chef's skill on the shared nutrition classpath) to prove it is NOT routed.
    private final SkillRegistry skills = new SkillRegistry(List.of(
            skill("diet-profiler", "Set the person's diet profile: goals, allergies, kcal target."),
            skill("nutrition-analyst", "Analyse how the person has been eating from their meal log."),
            skill("meal-planner", "Plan a ration / weekly menu and a shopping list."),
            skill("basket-analyst", "Break down a grocery basket into КБЖУ + good/watch/cut."),
            skill("meal-logger", "Log what the person ate into their food log."),
            skill("recipe-finder", "Find a recipe (chef-agent's skill, not a nutritionist intent).")));

    private final NutritionIntentRouter router = new NutritionIntentRouter(
            llm, skills, classifier, manifest, foodLogger, dietProfiler, nutritionAnalyst,
            mealPlanner, basketBreakdown, chat);

    @Test
    void routesToDietProfiler() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"diet-profiler\"}")));
        when(dietProfiler.setProfile(any())).thenReturn(Mono.just(sentinel("profiler")));

        StepVerifier.create(router.route(msg("моя цель — 1800 ккал в день, без лактозы")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("profiler"))
                .verifyComplete();
        verify(dietProfiler).setProfile(any());
    }

    @Test
    void routesToNutritionAnalyst() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"nutrition-analyst\"}")));
        when(nutritionAnalyst.analyse(any())).thenReturn(Mono.just(sentinel("analyst")));

        StepVerifier.create(router.route(msg("разбери как я питаюсь за неделю")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("analyst"))
                .verifyComplete();
        verify(nutritionAnalyst).analyse(any());
    }

    @Test
    void routesToMealPlannerOwnCutByDefault() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"meal-planner\"}")));
        when(mealPlanner.plan(any(), eq(false))).thenReturn(Mono.just(sentinel("planner")));

        StepVerifier.create(router.route(msg("составь мне рацион на неделю")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("planner"))
                .verifyComplete();
        verify(mealPlanner).plan(any(), eq(false));      // no family cue → own cut
    }

    @Test
    void familyCueWidensTheRationScope() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"meal-planner\"}")));
        when(mealPlanner.plan(any(), eq(true))).thenReturn(Mono.just(sentinel("planner-shared")));

        StepVerifier.create(router.route(msg("составь рацион на всю семью")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("planner-shared"))
                .verifyComplete();
        verify(mealPlanner).plan(any(), eq(true));       // "на всю семью" → personal ∪ shared
    }

    @Test
    void routesToBasketAnalyst() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"basket-analyst\"}")));
        when(basketBreakdown.breakdownText(any())).thenReturn(Mono.just(sentinel("basket")));

        StepVerifier.create(router.route(msg("разбери мою корзину продуктов")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("basket"))
                .verifyComplete();
        verify(basketBreakdown).breakdownText(any());
    }

    @Test
    void routesToMealLogger() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"meal-logger\"}")));
        when(foodLogger.logText(any())).thenReturn(Mono.just(sentinel("logger")));

        StepVerifier.create(router.route(msg("на обед съел борщ и хлеб")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("logger"))
                .verifyComplete();
        verify(foodLogger).logText(any());
    }

    @Test
    void chatDecisionFallsBackToNutritionistChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"chat\",\"text\":\"Чем помочь с питанием?\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("привет")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        verify(dietProfiler, never()).setProfile(any());
    }

    @Test
    void nonJsonProseFallsBackToChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("Извините, не понял.")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("???")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
    }

    @Test
    void recipeFinderSkillNameFallsBackToChatAndIsNotAdvertised() {
        // recipe-finder is chef's skill, not in the dispatch map → the router must not dispatch it.
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                reply("{\"action\":\"skill\",\"name\":\"recipe-finder\"}")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("найди рецепт борща")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        assertThat(router.buildClassifierPrompt())
                .contains("diet-profiler").contains("meal-planner").doesNotContain("recipe-finder");
    }

    @Test
    void blankMessageSkipsTheLlmAndChats() {
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("   ")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
        verify(llm, never()).chat(any());
    }

    @Test
    void llmErrorFallsBackToChat() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.error(new RuntimeException("gateway down")));
        when(chat.reply(any())).thenReturn(Mono.just(sentinel("chat")));

        StepVerifier.create(router.route(msg("составь рацион")))
                .assertNext(r -> assertThat(r.text()).isEqualTo("chat"))
                .verifyComplete();
        verify(chat).reply(any());
    }

    private static Skill skill(String name, String description) {
        return new Skill(name, description, "0.1.0", "nutrition", List.of(), List.of("en", "ru"), "body");
    }

    private IntentResponse sentinel(String tag) {
        return new IntentResponse("nutritionist", tag, null);
    }

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-large", text, "stop", new LlmUsage(10, 5, 15));
    }
}
