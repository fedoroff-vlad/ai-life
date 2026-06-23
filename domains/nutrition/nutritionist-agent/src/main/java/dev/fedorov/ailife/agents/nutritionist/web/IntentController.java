package dev.fedorov.ailife.agents.nutritionist.web;

import dev.fedorov.ailife.agents.nutritionist.chat.NutritionistChat;
import dev.fedorov.ailife.agents.nutritionist.foodlog.FoodLogger;
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
 *   <li>a photo attachment → {@link FoodLogger#logPhoto} (meal photo → caption extract → log) — the
 *       default for a photo until NU-f adds a basket-cue split (mirrors stylist's catalogue default);</li>
 *   <li>a typed message with a food-log cue ("съел…", "на обед…", "запиши…") → {@link FoodLogger#logText}
 *       (one LLM extract → log);</li>
 *   <li>otherwise → the {@link NutritionistChat} fallback.</li>
 * </ul>
 * The cue split is a deterministic keyword heuristic — good enough for the MVP, MockWebServer-testable,
 * and replaceable by an LLM classifier later. Basket breakdown / ration land in NU-f / NU-g.
 */
@RestController
@RequestMapping("/agents/nutritionist")
public class IntentController {

    private static final Set<String> LOG_CUES = Set.of(
            "съел", "съела", "поел", "поела", "я ел", "я ела", "перекус",
            "на завтрак", "на обед", "на ужин", "на полдник", "запиши", "записать",
            "log meal", "log my", "i ate", "i had", "for breakfast", "for lunch", "for dinner");

    private final FoodLogger foodLogger;
    private final NutritionistChat chat;

    public IntentController(FoodLogger foodLogger, NutritionistChat chat) {
        this.foodLogger = foodLogger;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> image = attachment(message, "image");
        if (image.isPresent()) {
            return foodLogger.logPhoto(message, image.get().storageUri());
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
