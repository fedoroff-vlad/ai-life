package dev.fedorov.ailife.agents.nutritionist.basket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.nutritionist.config.NutritionistAgentProperties;
import dev.fedorov.ailife.agents.nutritionist.http.BasketClient;
import dev.fedorov.ailife.agents.nutritionist.http.CaptionClient;
import dev.fedorov.ailife.agents.nutritionist.http.DietProfileClient;
import dev.fedorov.ailife.agents.nutritionist.http.MediaStoreClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.nutrition.BasketItem;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.SaveBasketInput;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.RenderedDoc;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The basket-breakdown flow (NU-f, direct path) — the headline of the nutrition domain. A grocery
 * basket sent straight to the nutritionist as a <b>photo</b> (receipt / a pile of products) or a
 * <b>typed list</b> is turned into a КБЖУ breakdown with a good / watch / cut verdict against the
 * person's diet profile, then persisted and delivered as an HTML report.
 *
 * <p>One extraction-and-breakdown pass via the {@code basket-analyst} SKILL (the diet profile is
 * folded into the instruction so the verdicts respect goals/restrictions): a photo runs through the
 * shared {@code mcp-media-processing} {@code caption} tool (the vision call lives once in the
 * capability-MCP); a typed list runs one llm-gateway {@code DEFAULT} turn. The parsed draft is saved
 * via {@code mcp-nutrition}'s {@code POST /internal/basket} (NU-f1), rendered to a verdict board
 * through the shared {@link DocRenderer} ({@code libs/doc-render}), stored in media-service, and
 * returned as a link. Any stage failing degrades to a friendly message.
 *
 * <p>The automatic fan-out (a grocery receipt reaching finance <i>and</i> nutrition off the bus) is
 * the IA slice on top; this direct path lands first.
 */
@Component
public class BasketBreakdown {

    private static final Logger log = LoggerFactory.getLogger(BasketBreakdown.class);
    private static final String SKILL_NAME = "basket-analyst";

    private final CaptionClient caption;
    private final LlmClient llm;
    private final DietProfileClient profiles;
    private final BasketClient baskets;
    private final MediaStoreClient media;
    private final DocRenderer renderer;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final NutritionistAgentProperties props;

    public BasketBreakdown(CaptionClient caption,
                           LlmClient llm,
                           DietProfileClient profiles,
                           BasketClient baskets,
                           MediaStoreClient media,
                           DocRenderer renderer,
                           SkillRegistry skills,
                           AgentManifest manifest,
                           ObjectMapper json,
                           NutritionistAgentProperties props) {
        this.caption = caption;
        this.llm = llm;
        this.profiles = profiles;
        this.baskets = baskets;
        this.media = media;
        this.renderer = renderer;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.props = props;
    }

    /** A basket photo (receipt / products) → caption extract + breakdown → save + report. */
    public Mono<IntentResponse> breakdownPhoto(NormalizedMessage msg, String mediaId) {
        return profile(msg).flatMap(prof -> caption
                        .caption(mediaId, instruction(msg.text(), prof.orElse(null)))
                        .flatMap(r -> persist(msg, parseMediaId(mediaId), "receipt", r.text(), r.model())))
                .onErrorResume(e -> {
                    log.warn("basket breakdown (photo) failed for media {}: {}", mediaId, e.toString());
                    return Mono.just(reply(
                            "Не удалось разобрать корзину по фото. Пришлите чёткий снимок чека или продуктов.", null));
                });
    }

    /** A typed grocery list → one LLM extract + breakdown → save + report. */
    public Mono<IntentResponse> breakdownText(NormalizedMessage msg) {
        return profile(msg).flatMap(prof -> {
            LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                    LlmMessage.system(instruction(null, prof.orElse(null))),
                    LlmMessage.user(msg.text())));
            return llm.chat(request)
                    .flatMap(r -> persist(msg, null, "manual", r.content(), r.model()));
        }).onErrorResume(e -> {
            log.warn("basket breakdown (text) failed: {}", e.toString());
            return Mono.just(reply("Не удалось разобрать список продуктов. Попробуйте позже.", null));
        });
    }

    private Mono<Optional<DietProfileDto>> profile(NormalizedMessage msg) {
        return profiles.get(msg.householdId(), msg.userId())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorReturn(Optional.empty());
    }

    private Mono<IntentResponse> persist(NormalizedMessage msg, UUID receiptMediaId, String source,
                                         String llmContent, String model) {
        JsonNode draft = parseDraft(llmContent);
        if (draft == null) {
            return Mono.just(reply(
                    "Не понял, что в корзине. Пришлите фото чека/продуктов или список текстом.", model));
        }
        List<BasketItem> items = parseItems(draft.get("items"));
        if (items.isEmpty()) {
            return Mono.just(reply(
                    "Не нашёл продуктов в корзине. Пришлите фото чёткого чека или перечислите покупки.", model));
        }
        JsonNode totals = draft.get("totals");
        JsonNode analysis = draft.hasNonNull("analysis") ? draft.get("analysis") : null;
        SaveBasketInput input = new SaveBasketInput(
                msg.householdId(),
                msg.userId(),
                null,
                text(draft, "merchant"),
                source,
                receiptMediaId,
                items,
                intOrNull(totals, "kcal"),
                decimalOrNull(totals, "protein_g"),
                decimalOrNull(totals, "fat_g"),
                decimalOrNull(totals, "carbs_g"),
                analysis);
        return baskets.save(input)
                .flatMap(saved -> store(msg, draft, items)
                        .map(link -> reply(summary(draft, analysis) + "\n\nРазбор корзины: " + link, model))
                        .onErrorResume(e -> {
                            log.warn("basket render/store failed: {}", e.toString());
                            return Mono.just(reply(summary(draft, analysis), model));
                        }))
                .onErrorResume(e -> {
                    log.warn("save_basket write failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить разбор корзины. Попробуйте позже.", null));
                });
    }

    /** Render the verdict board (good/watch/cut tiles + КБЖУ totals + summary), store, link. */
    private Mono<String> store(NormalizedMessage msg, JsonNode draft, List<BasketItem> items) {
        Doc.Builder b = Doc.builder("Разбор корзины")
                .kicker("КБЖУ · Хорошо · Осторожно · Убрать");
        String merchant = text(draft, "merchant");
        b.subtitle(merchant != null && !merchant.isBlank()
                ? merchant + " · " + items.size() + " позиц." : items.size() + " позиций");

        addVerdicts(b, draft.path("analysis").get("good"), Doc.Verdict.KEEP);
        addVerdicts(b, draft.path("analysis").get("watch"), Doc.Verdict.QUESTION);
        addVerdicts(b, draft.path("analysis").get("cut"), Doc.Verdict.REMOVE);

        List<String> totals = totalsLines(draft.get("totals"));
        if (!totals.isEmpty()) b.section("Итоги (КБЖУ)", totals);

        String summary = text(draft, "summary");
        if (summary != null && !summary.isBlank()) b.section("Вывод", List.of(summary));

        RenderedDoc rendered = renderer.render(b.build());
        return media.upload(msg.householdId(), msg.userId(),
                        rendered.filename(), rendered.mimeType(), rendered.content())
                .map(this::link);
    }

    private static void addVerdicts(Doc.Builder b, JsonNode arr, Doc.Verdict verdict) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            if (n.isObject()) {
                String name = text(n, "name");
                if (name != null && !name.isBlank()) b.verdict(name, verdict, text(n, "reason"), null);
            } else if (n.isTextual() && !n.asText().isBlank()) {
                b.verdict(n.asText(), verdict, null, null);
            }
        }
    }

    private List<BasketItem> parseItems(JsonNode arr) {
        List<BasketItem> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String name = text(n, "name");
            if (name == null || name.isBlank()) continue;
            out.add(new BasketItem(name, text(n, "qty"),
                    intOrNull(n, "kcal"), decimalOrNull(n, "protein_g"),
                    decimalOrNull(n, "fat_g"), decimalOrNull(n, "carbs_g")));
        }
        return out;
    }

    private static List<String> totalsLines(JsonNode totals) {
        List<String> out = new ArrayList<>();
        if (totals == null || !totals.isObject()) return out;
        Integer kcal = intOrNull(totals, "kcal");
        if (kcal != null) out.add("Калорийность корзины: ~" + kcal + " ккал.");
        List<String> macros = new ArrayList<>();
        addMacro(macros, "белки", decimalOrNull(totals, "protein_g"));
        addMacro(macros, "жиры", decimalOrNull(totals, "fat_g"));
        addMacro(macros, "углеводы", decimalOrNull(totals, "carbs_g"));
        if (!macros.isEmpty()) out.add(String.join(", ", macros) + " (г).");
        return out;
    }

    private static void addMacro(List<String> out, String label, BigDecimal value) {
        if (value != null) out.add(label + " " + value.stripTrailingZeros().toPlainString());
    }

    /** Chat summary: the SKILL's one-line verdict, else a count of good/watch/cut. */
    private String summary(JsonNode draft, JsonNode analysis) {
        String s = text(draft, "summary");
        if (s != null && !s.isBlank()) {
            return s.length() > 280 ? s.substring(0, 277) + "…" : s;
        }
        int good = count(analysis, "good");
        int watch = count(analysis, "watch");
        int cut = count(analysis, "cut");
        return "Разобрал корзину: " + good + " хорошо, " + watch + " осторожно, " + cut + " убрать.";
    }

    private static int count(JsonNode analysis, String key) {
        if (analysis == null) return 0;
        JsonNode arr = analysis.get(key);
        return (arr != null && arr.isArray()) ? arr.size() : 0;
    }

    /**
     * The instruction handed to the {@code caption} tool / used as the system prompt: the
     * {@code basket-analyst} SKILL.md, the diet profile folded in (so the verdicts respect
     * goals/restrictions), plus the user's note when present.
     */
    private String instruction(String userText, DietProfileDto profile) {
        StringBuilder sb = new StringBuilder(skillBody());
        if (profile != null) {
            try {
                sb.append("\n\nDiet profile (учитывай цели и ограничения при оценке good/watch/cut):\n")
                        .append(json.writeValueAsString(profile));
            } catch (Exception ignored) {
                // a serialisation hiccup just drops the profile constraint
            }
        }
        String note = (userText == null || userText.isBlank()) ? null : userText;
        if (note != null) sb.append("\n\nUser note: ").append(note);
        return sb.toString();
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "basket-analyst SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private JsonNode parseDraft(String content) {
        if (content == null) return null;
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            if (!node.isObject() || node.hasNonNull("error")) return null;
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    private String link(MediaObjectDto stored) {
        return base() + "/v1/media/" + stored.id();
    }

    private String base() {
        String base = props.getPublicMediaBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v != null && v.isNumber()) ? v.asInt() : null;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v != null && v.isNumber()) ? v.decimalValue() : null;
    }

    private static UUID parseMediaId(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) return null;
        try {
            return UUID.fromString(mediaId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
