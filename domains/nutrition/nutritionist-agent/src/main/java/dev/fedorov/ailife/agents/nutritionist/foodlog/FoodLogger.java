package dev.fedorov.ailife.agents.nutritionist.foodlog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.nutritionist.http.CaptionClient;
import dev.fedorov.ailife.agents.nutritionist.http.MealClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.nutrition.LogMealInput;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turns a meal — a <b>photo</b> or a <b>typed description</b> — into a logged {@code meal_log} row,
 * <b>write-immediately</b> (a food diary is appended to constantly; edits/deletes go through the
 * tools later).
 *
 * <p>Both paths share the {@code meal-logger} SKILL.md as the extraction prompt and the same
 * lenient-JSON parse → write via {@code mcp-nutrition}'s {@code POST /internal/meal} (NU-c1):
 * <ul>
 *   <li><b>photo</b> → the SKILL is the instruction for the shared {@code mcp-media-processing}
 *       {@code caption} tool (the vision call lives once in the capability-MCP — same doctrine as
 *       finance's receipt-parser / stylist's cataloguer);</li>
 *   <li><b>typed</b> → the SKILL is the system prompt for one llm-gateway {@code DEFAULT} turn over
 *       the user's text.</li>
 * </ul>
 * The meal is attributed to the sender ({@code ownerId = userId}) so per-person logging works from
 * the start. Any stage failing degrades to a friendly user-facing message.
 */
@Component
public class FoodLogger {

    private static final Logger log = LoggerFactory.getLogger(FoodLogger.class);
    private static final String SKILL_NAME = "meal-logger";

    private final CaptionClient caption;
    private final MealClient meals;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public FoodLogger(CaptionClient caption, MealClient meals, LlmClient llm,
                      SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.caption = caption;
        this.meals = meals;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    /** A meal photo → caption extract → write. */
    public Mono<IntentResponse> logPhoto(NormalizedMessage msg, String mediaId) {
        return caption.caption(mediaId, captionInstruction(msg.text()))
                .flatMap(result -> write(msg, parseMediaId(mediaId), "photo", result.text(), result.model()))
                .onErrorResume(e -> {
                    log.warn("food-log (photo) failed for media {}: {}", mediaId, e.toString());
                    return Mono.just(reply("Не удалось разобрать фото еды. Пришлите ещё раз почётче.", null));
                });
    }

    /** A typed meal description → one LLM extract → write. */
    public Mono<IntentResponse> logText(NormalizedMessage msg) {
        // temperature=0: extraction must be deterministic/faithful, not creative.
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(msg.text())), 0.0);
        return llm.chat(request)
                .flatMap(r -> write(msg, null, "text", r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("food-log (text) failed: {}", e.toString());
                    return Mono.just(reply("Не удалось записать приём пищи. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> write(NormalizedMessage msg, UUID imageMediaId, String source,
                                       String llmContent, String model) {
        Draft draft = parseDraft(llmContent);
        if (draft == null || blankToNull(draft.description()) == null) {
            return Mono.just(reply(
                    "Не понял, что это за еда. Опишите блюдо или пришлите фото поразборчивее.", model));
        }
        LogMealInput input = new LogMealInput(
                msg.householdId(),
                msg.userId(),               // attribute to the sender (per-person logging)
                Instant.now(),
                source,
                draft.description(),
                draft.items(),
                draft.kcal(),
                draft.proteinG(),
                draft.fatG(),
                draft.carbsG(),
                imageMediaId);
        return meals.log(input)
                .map(saved -> reply(successText(saved.description(), saved.kcal()), model))
                .onErrorResume(e -> {
                    log.warn("log_meal write failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить приём пищи. Попробуйте позже.", null));
                });
    }

    /**
     * The instruction handed to the capability's {@code caption} tool: the {@code meal-logger}
     * SKILL.md (the self-contained strict-JSON extract prompt), plus the user's own caption as a
     * trailing hint when present (e.g. "это мой обед, порция большая").
     */
    private String captionInstruction(String userText) {
        String note = blankToNull(userText);
        return note == null ? skillBody() : skillBody() + "\n\nUser note: " + note;
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "meal-logger SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private Draft parseDraft(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            if (!node.isObject() || node.hasNonNull("error")) {
                return null;
            }
            return new Draft(
                    text(node, "description"),
                    node.hasNonNull("items") ? node.get("items") : null,
                    intOrNull(node, "kcal"),
                    decimalOrNull(node, "protein_g"),
                    decimalOrNull(node, "fat_g"),
                    decimalOrNull(node, "carbs_g"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isNumber() ? node.get(field).asInt() : null;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isNumber()
                ? node.get(field).decimalValue() : null;
    }

    /** The media id arrives as the attachment's storageUri (a UUID string); null if unparseable. */
    private static UUID parseMediaId(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(mediaId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String successText(String description, Integer kcal) {
        StringBuilder sb = new StringBuilder("Записал: ").append(description);
        if (kcal != null) sb.append(" (~").append(kcal).append(" ккал)");
        sb.append(". Поправьте, если что-то не так.");
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    private record Draft(String description, JsonNode items, Integer kcal,
                         BigDecimal proteinG, BigDecimal fatG, BigDecimal carbsG) {
    }
}
