package dev.fedorov.ailife.agents.nutritionist.basket;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.nutritionist.http.BasketClient;
import dev.fedorov.ailife.agents.nutritionist.http.CaptionClient;
import dev.fedorov.ailife.agents.nutritionist.http.DietProfileClient;
import dev.fedorov.ailife.agents.nutritionist.http.FoodDataClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.basket.BasketCapturedEvent;
import dev.fedorov.ailife.contracts.food.FoodFacts;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.nutrition.BasketItem;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.SaveBasketInput;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
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
 *
 * <p>FD-c: before rendering, the board is grounded in real reference data — each item is looked up in
 * the shared {@code mcp-food-data} capability (Open Food Facts) for precise per-100g КБЖУ, folded in
 * as a "Точные КБЖУ" section. The lookups are read-only, soft-failed per item, and bounded; a
 * no-match just omits the section (the LLM's own estimate still ships). Both the direct and the
 * bus-fan-out paths enrich, since they share {@code render}.
 */
@Component
public class BasketBreakdown {

    private static final Logger log = LoggerFactory.getLogger(BasketBreakdown.class);
    private static final String SKILL_NAME = "basket-analyst";
    /** Bound the food-facts fan-out so a long receipt can't trigger an unbounded lookup storm. */
    private static final int MAX_LOOKUPS = 40;
    /** Cap concurrent lookups so the enrichment stays a courteous neighbour to Open Food Facts. */
    private static final int LOOKUP_CONCURRENCY = 6;

    private final CaptionClient caption;
    private final LlmClient llm;
    private final DietProfileClient profiles;
    private final BasketClient baskets;
    private final FoodDataClient food;
    private final DeliverablePublisher publisher;
    private final ProfileClient people;
    private final NotifierClient notifier;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public BasketBreakdown(CaptionClient caption,
                           LlmClient llm,
                           DietProfileClient profiles,
                           BasketClient baskets,
                           FoodDataClient food,
                           DeliverablePublisher publisher,
                           ProfileClient people,
                           NotifierClient notifier,
                           SkillRegistry skills,
                           AgentManifest manifest,
                           ObjectMapper json) {
        this.caption = caption;
        this.llm = llm;
        this.profiles = profiles;
        this.baskets = baskets;
        this.food = food;
        this.publisher = publisher;
        this.people = people;
        this.notifier = notifier;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
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
        return profile(msg.householdId(), msg.userId());
    }

    private Mono<Optional<DietProfileDto>> profile(UUID householdId, UUID ownerId) {
        return profiles.get(householdId, ownerId)
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
        JsonNode analysis = draft.hasNonNull("analysis") ? draft.get("analysis") : null;
        return saveAndRender(msg.householdId(), msg.userId(), source, receiptMediaId,
                        text(draft, "merchant"), draft, items)
                .map(link -> reply(summary(draft, analysis) + "\n\nРазбор корзины: " + link, model))
                .onErrorResume(e -> {
                    log.warn("basket save/render failed: {}", e.toString());
                    return Mono.just(reply(summary(draft, analysis), model));
                });
    }

    /** Save the analysed basket, render the verdict board, store it, and return the public link. */
    private Mono<String> saveAndRender(UUID householdId, UUID ownerId, String source, UUID receiptMediaId,
                                       String merchant, JsonNode draft, List<BasketItem> items) {
        JsonNode totals = draft.get("totals");
        JsonNode analysis = draft.hasNonNull("analysis") ? draft.get("analysis") : null;
        SaveBasketInput input = new SaveBasketInput(
                householdId, ownerId, null, merchant, source, receiptMediaId, items,
                intOrNull(totals, "kcal"), decimalOrNull(totals, "protein_g"),
                decimalOrNull(totals, "fat_g"), decimalOrNull(totals, "carbs_g"), analysis);
        return baskets.save(input)
                .flatMap(saved -> render(householdId, ownerId, merchant, draft, items));
    }

    /** Render the verdict board (good/watch/cut tiles + КБЖУ totals + precise facts + summary), store, link. */
    private Mono<String> render(UUID householdId, UUID ownerId, String merchant,
                                JsonNode draft, List<BasketItem> items) {
        return enrichFacts(items).flatMap(facts -> {
            Doc.Builder b = Doc.builder("Разбор корзины")
                    .kicker("КБЖУ · Хорошо · Осторожно · Убрать");
            b.subtitle(merchant != null && !merchant.isBlank()
                    ? merchant + " · " + items.size() + " позиц." : items.size() + " позиций");

            addVerdicts(b, draft.path("analysis").get("good"), Doc.Verdict.KEEP);
            addVerdicts(b, draft.path("analysis").get("watch"), Doc.Verdict.QUESTION);
            addVerdicts(b, draft.path("analysis").get("cut"), Doc.Verdict.REMOVE);

            List<String> totals = totalsLines(draft.get("totals"));
            if (!totals.isEmpty()) b.section("Итоги (КБЖУ)", totals);

            List<String> factsLines = factsLines(facts);
            if (!factsLines.isEmpty()) b.section("Точные КБЖУ (Open Food Facts, на 100 г)", factsLines);

            String summary = text(draft, "summary");
            if (summary != null && !summary.isBlank()) b.section("Вывод", List.of(summary));

            return publisher.publish(householdId, ownerId, b.build());
        });
    }

    /**
     * FD-c: ground the breakdown in real reference data. For each basket item, look up precise
     * per-100g macros from the shared {@code mcp-food-data} capability (Open Food Facts) in parallel,
     * keeping only the products that matched. Read-only enrichment, <b>soft-failed per item</b> (a
     * missing/erroring lookup just drops out), capped and concurrency-bounded so a long receipt stays
     * a courteous neighbour. Returns an empty list when nothing matched — the board simply omits the
     * facts section (the LLM's own КБЖУ estimate still ships).
     */
    private Mono<List<FoodFacts>> enrichFacts(List<BasketItem> items) {
        if (items == null || items.isEmpty()) {
            return Mono.just(List.of());
        }
        List<String> names = new ArrayList<>();
        for (BasketItem it : items) {
            if (it.name() != null && !it.name().isBlank() && names.size() < MAX_LOOKUPS) {
                names.add(it.name());
            }
        }
        if (names.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(names)
                .flatMap(name -> food.lookup(name)
                        .onErrorResume(e -> {
                            log.debug("food-lookup failed for '{}': {}", name, e.toString());
                            return Mono.empty();
                        }), LOOKUP_CONCURRENCY)
                .filter(f -> f != null && f.name() != null && !f.name().isBlank())
                .collectList();
    }

    /** One readable line per matched product: "name (brand) — N ккал, Б.. Ж.. У.. · Nutri-Score X". */
    private static List<String> factsLines(List<FoodFacts> facts) {
        List<String> out = new ArrayList<>();
        if (facts == null) {
            return out;
        }
        for (FoodFacts f : facts) {
            List<String> macros = new ArrayList<>();
            if (f.kcal100g() != null) macros.add(f.kcal100g() + " ккал");
            addBd(macros, "Б", f.protein100g());
            addBd(macros, "Ж", f.fat100g());
            addBd(macros, "У", f.carbs100g());
            if (macros.isEmpty()) continue; // matched but no macros on file — nothing to ground
            StringBuilder sb = new StringBuilder(f.name());
            if (f.brand() != null && !f.brand().isBlank()) sb.append(" (").append(f.brand()).append(")");
            sb.append(" — ").append(String.join(", ", macros));
            if (f.nutriScore() != null && !f.nutriScore().isBlank()) {
                sb.append(" · Nutri-Score ").append(f.nutriScore().toUpperCase());
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static void addBd(List<String> out, String label, BigDecimal value) {
        if (value != null) out.add(label + " " + value.stripTrailingZeros().toPlainString());
    }

    /**
     * IA-b: a grocery {@code basket.captured} event consumed off the bus (mcp-nutrition forwards it
     * here over {@code POST /internal/basket-event}). Finance already extracted the line items once,
     * so we don't re-run vision — we run one LLM breakdown over the item list (the {@code
     * basket-analyst} SKILL, diet profile folded in) → save the basket → render the verdict board →
     * <b>notify every household member</b> with the summary + link (there's no user reply channel on
     * a bus consume, so this fans out like the gift-recommender). Best-effort: any failure is logged
     * and swallowed (the breakdown is a bonus on top of the finance expense).
     */
    public Mono<Void> breakdownFromEvent(BasketCapturedEvent event) {
        if (event == null || event.householdId() == null
                || event.items() == null || event.items().isEmpty()) {
            return Mono.empty();
        }
        List<BasketItem> evItems = event.items();
        return profile(event.householdId(), event.ownerId()).flatMap(prof -> {
            LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                    LlmMessage.system(instruction(null, prof.orElse(null))),
                    LlmMessage.user(itemsToText(event.merchant(), evItems))));
            return llm.chat(request).flatMap(r -> {
                JsonNode draft = parseDraft(r.content());
                if (draft == null) {
                    log.warn("basket.captured breakdown: LLM returned no usable analysis for household {}",
                            event.householdId());
                    return Mono.<Void>empty();
                }
                List<BasketItem> items = parseItems(draft.get("items"));
                if (items.isEmpty()) {
                    items = evItems;   // fall back to the receipt's own line items
                }
                String draftMerchant = text(draft, "merchant");
                String merchant = (draftMerchant != null && !draftMerchant.isBlank())
                        ? draftMerchant : event.merchant();
                JsonNode analysis = draft.hasNonNull("analysis") ? draft.get("analysis") : null;
                return saveAndRender(event.householdId(), event.ownerId(), "receipt",
                                event.receiptMediaId(), merchant, draft, items)
                        .flatMap(link -> notifyHousehold(event.householdId(),
                                "🧺 " + summary(draft, analysis) + "\n\nРазбор корзины: " + link));
            });
        }).onErrorResume(e -> {
            log.warn("basket.captured breakdown failed for household {}: {}",
                    event.householdId(), e.toString());
            return Mono.empty();
        });
    }

    /** Fan the breakdown out to every household member (no user reply channel on a bus consume). */
    private Mono<Void> notifyHousehold(UUID householdId, String text) {
        return people.usersByHousehold(householdId)
                .flatMap(u -> notifier.notify(u.id(), text)
                        .onErrorResume(e -> {
                            log.warn("notify household member {} failed: {}", u.id(), e.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    /** A grocery list rendered as text for the LLM breakdown (the items finance already read). */
    private static String itemsToText(String merchant, List<BasketItem> items) {
        StringBuilder sb = new StringBuilder();
        if (merchant != null && !merchant.isBlank()) {
            sb.append("Чек из «").append(merchant).append("». Продукты:\n");
        } else {
            sb.append("Продукты в корзине:\n");
        }
        for (BasketItem it : items) {
            sb.append("- ").append(it.name());
            if (it.qty() != null && !it.qty().isBlank()) sb.append(" (").append(it.qty()).append(")");
            sb.append("\n");
        }
        return sb.toString();
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
