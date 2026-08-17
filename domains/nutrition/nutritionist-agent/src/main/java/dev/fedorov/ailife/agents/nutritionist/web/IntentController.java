package dev.fedorov.ailife.agents.nutritionist.web;

import dev.fedorov.ailife.agents.nutritionist.basket.BasketBreakdown;
import dev.fedorov.ailife.agents.nutritionist.foodlog.FoodLogger;
import dev.fedorov.ailife.agents.nutritionist.intent.NutritionIntentRouter;
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
 * Hit by the orchestrator when intent routing selects {@code nutritionist}:
 * <ul>
 *   <li>a photo attachment with a basket cue ("продукты", "корзина", "чек", "закупка") →
 *       {@link BasketBreakdown#breakdownPhoto} (basket photo → КБЖУ + good/watch/cut → HTML report);</li>
 *   <li>any other photo attachment → {@link FoodLogger#logPhoto} (meal photo → caption extract → log);</li>
 *   <li>otherwise → {@link NutritionIntentRouter}, which classifies the text (via the shared
 *       {@code SkillClassifier}) into one of the five intent flows — diet profile / nutrition analysis /
 *       ration plan / basket breakdown / meal log — or a plain chat reply.</li>
 * </ul>
 * The photo check comes first (a photo is unambiguously an ingest, not a text intent, so it stays a
 * deterministic pre-check rather than an LLM classification; the basket-vs-meal photo split is likewise a
 * deterministic keyword on the attachment path). The typed-message keyword-cue heuristic this controller
 * used to carry was replaced by the LLM classifier in skills-vs-flows Bucket 1 (#475), so the routing SSOT
 * is each intent skill's SKILL.md description; the family/own read-scope for the ration stays a
 * deterministic cue inside the planner flow ({@link NutritionIntentRouter}). The automatic grocery-receipt
 * fan-out (finance → nutrition off the bus) is the IA slice.
 */
@RestController
@RequestMapping("/agents/nutritionist")
public class IntentController {

    private static final Set<String> BASKET_CUES = Set.of(
            "продукт", "корзин", "закуп", "чек", "покупк", "список покупок", "разбери корзину",
            "разбери продукты", "что купить", "купил продукты", "купила продукты",
            "groceries", "grocery", "shopping list", "basket", "receipt", "break down my groceries");

    private final BasketBreakdown basketBreakdown;
    private final FoodLogger foodLogger;
    private final NutritionIntentRouter router;

    public IntentController(BasketBreakdown basketBreakdown, FoodLogger foodLogger,
                            NutritionIntentRouter router) {
        this.basketBreakdown = basketBreakdown;
        this.foodLogger = foodLogger;
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> image = attachment(message, "image");
        if (image.isPresent()) {
            return isMatch(message.text(), BASKET_CUES)
                    ? basketBreakdown.breakdownPhoto(message, image.get().storageUri())
                    : foodLogger.logPhoto(message, image.get().storageUri());
        }
        return router.route(message);
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
