package dev.fedorov.ailife.agents.nutritionist.web;

import dev.fedorov.ailife.agents.nutritionist.analysis.NutritionAnalyst;
import dev.fedorov.ailife.agents.nutritionist.basket.BasketBreakdown;
import dev.fedorov.ailife.agents.nutritionist.chat.NutritionistChat;
import dev.fedorov.ailife.agents.nutritionist.foodlog.FoodLogger;
import dev.fedorov.ailife.agents.nutritionist.profile.DietProfiler;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Hit by the orchestrator when intent routing selects {@code nutritionist}. The NU-c food-log flow:
 * <ul>
 *   <li>a photo attachment with a basket cue ("продукты", "корзина", "чек", "закупка") →
 *       {@link BasketBreakdown#breakdownPhoto} (basket photo → КБЖУ + good/watch/cut → HTML report);</li>
 *   <li>any other photo attachment → {@link FoodLogger#logPhoto} (meal photo → caption extract → log);</li>
 *   <li>a typed message with a diet-profile cue ("моя цель…", "у меня аллергия…", "ккал в день…") →
 *       {@link DietProfiler#setProfile} (one LLM extract → upsert the profile);</li>
 *   <li>a typed message with an analysis cue ("разбор питания", "как я питаюсь") →
 *       {@link NutritionAnalyst#analyse} (gather meals + profile → synthesis → HTML board);</li>
 *   <li>a typed message with a basket cue ("разбери продукты", "список покупок") →
 *       {@link BasketBreakdown#breakdownText} (typed list → КБЖУ + good/watch/cut → HTML report);</li>
 *   <li>a typed message with a food-log cue ("съел…", "на обед…", "запиши…") → {@link FoodLogger#logText}
 *       (one LLM extract → log);</li>
 *   <li>otherwise → the {@link NutritionistChat} fallback.</li>
 * </ul>
 * The cue split is a deterministic keyword heuristic — good enough for the MVP, MockWebServer-testable,
 * and replaceable by an LLM classifier later. Ration / shopping list lands in NU-g; the automatic
 * grocery-receipt fan-out (finance → nutrition off the bus) is the IA slice.
 */
@RestController
@RequestMapping("/agents/nutritionist")
public class IntentController {

    private static final Set<String> PROFILE_CUES = Set.of(
            "моя цель", "мои цели", "цель по", "ккал в день", "калорий в день", "у меня аллергия",
            "аллергия на", "я вегетар", "я веган", "халяль", "без глютена", "без лактозы",
            "установи цель", "мой профиль", "профиль питания", "мои ограничения",
            "my goal", "my goals", "i'm allergic", "set my diet", "kcal a day", "kcal per day",
            "i'm vegan", "i'm vegetarian", "diet goal");

    private static final Set<String> ANALYSIS_CUES = Set.of(
            "разбор питания", "разбор рациона", "проанализируй питание", "проанализируй рацион",
            "анализ питания", "анализ рациона", "как я питаюсь", "как я ем", "оцени моё питание",
            "оцени мое питание", "что с моим питанием",
            "analyse my nutrition", "analyze my nutrition", "nutrition analysis", "how am i eating",
            "review my diet", "analyse my diet", "analyze my diet");

    private static final Set<String> BASKET_CUES = Set.of(
            "продукт", "корзин", "закуп", "чек", "покупк", "список покупок", "разбери корзину",
            "разбери продукты", "что купить", "купил продукты", "купила продукты",
            "groceries", "grocery", "shopping list", "basket", "receipt", "break down my groceries");

    private static final Set<String> LOG_CUES = Set.of(
            "съел", "съела", "поел", "поела", "я ел", "я ела", "перекус",
            "на завтрак", "на обед", "на ужин", "на полдник", "запиши", "записать",
            "log meal", "log my", "i ate", "i had", "for breakfast", "for lunch", "for dinner");

    private final FoodLogger foodLogger;
    private final DietProfiler dietProfiler;
    private final NutritionAnalyst nutritionAnalyst;
    private final BasketBreakdown basketBreakdown;
    private final NutritionistChat chat;

    public IntentController(FoodLogger foodLogger, DietProfiler dietProfiler,
                            NutritionAnalyst nutritionAnalyst, BasketBreakdown basketBreakdown,
                            NutritionistChat chat) {
        this.foodLogger = foodLogger;
        this.dietProfiler = dietProfiler;
        this.nutritionAnalyst = nutritionAnalyst;
        this.basketBreakdown = basketBreakdown;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> image = attachment(message, "image");
        if (image.isPresent()) {
            return isMatch(message.text(), BASKET_CUES)
                    ? basketBreakdown.breakdownPhoto(message, image.get().storageUri())
                    : foodLogger.logPhoto(message, image.get().storageUri());
        }
        if (isMatch(message.text(), PROFILE_CUES)) {
            return dietProfiler.setProfile(message);
        }
        if (isMatch(message.text(), ANALYSIS_CUES)) {
            return nutritionAnalyst.analyse(message);
        }
        if (isMatch(message.text(), BASKET_CUES)) {
            return basketBreakdown.breakdownText(message);
        }
        if (isMatch(message.text(), LOG_CUES)) {
            return foodLogger.logText(message);
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

    private static Optional<Attachment> attachment(NormalizedMessage message, String kind) {
        return message.attachments().stream()
                .filter(a -> kind.equals(a.kind()) && a.storageUri() != null)
                .findFirst();
    }
}
