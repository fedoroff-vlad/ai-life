package dev.fedorov.ailife.agents.nutritionist.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.nutritionist.config.NutritionistAgentProperties;
import dev.fedorov.ailife.agents.nutritionist.http.DietProfileClient;
import dev.fedorov.ailife.agents.nutritionist.http.MealReadClient;
import dev.fedorov.ailife.agents.nutritionist.http.MediaStoreClient;
import dev.fedorov.ailife.agents.nutritionist.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agents.nutritionist.http.WebSearchClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.RenderedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ration + shopping-list flow (NU-g) — the nutritionist's family meal-planning board, and the
 * second of the two driving cases ("закупиться в Ленте" → ration + shopping list). On a ration
 * request it gathers, in parallel on the shared {@link Coordinator}: the sender's <b>diet profile</b>,
 * the <b>household-default profile</b> (covers family members without their own — e.g. the wife / the
 * infant on прикорм), the person's <b>recent meals</b> (to avoid repetition / respect tastes), and —
 * when a store is named in the request — that store's <b>availability</b> (a cheap {@code mcp-web}
 * search). The Coordinator folds the successful sources into a {@code context} and runs one LLM
 * synthesis from {@code [AGENT.md, meal-planner SKILL.md] + {payload(request, season), context}} → a
 * multi-person ration + a grouped shopping list, which is rendered as an HTML board through the shared
 * {@link DocRenderer} seam, stored in media-service, and returned as a link.
 *
 * <p>Per-step soft-fail (no profile/store just drops that constraint). The ad-hoc people (wife,
 * infant) are read by the LLM from the request text — they aren't stored as owner rows. The
 * {@code meal-planner} SKILL carries the infant/medical-safety caveat. Synthesis failure → a friendly
 * message.
 *
 * <p><b>Ration → recipes (CH-b2):</b> once the ration is rendered, the flow invokes the <b>chef</b>'s
 * {@code recommend_recipes} over the orchestrator hub ({@code /v1/agents/invoke}) and folds the
 * returned recipe-card link into the reply — the gift-recommender→finance shape. Soft-failed: a chef
 * outage just drops the recipes line, the ration still ships.
 */
@Component
public class MealPlanner {

    private static final Logger log = LoggerFactory.getLogger(MealPlanner.class);
    private static final String SKILL_NAME = "meal-planner";
    private static final int MEAL_LIMIT = 20;
    private static final int STORE_HITS = 5;

    /** Store names that trigger the optional availability gather (the "закупиться в X" case). */
    private static final List<String> STORES = List.of(
            "лент", "ашан", "перекрёсток", "перекресток", "магнит", "пятёрочка", "пятерочка",
            "вкусвилл", "окей", "о'кей", "метро", "дикси", "спар", "глобус", "зельгрос",
            "lenta", "auchan", "metro", "spar", "globus", "selgros");

    private final Coordinator coordinator;
    private final DietProfileClient profiles;
    private final MealReadClient meals;
    private final WebSearchClient web;
    private final MediaStoreClient media;
    private final OrchestratorInvokeClient orchestrator;
    private final DocRenderer renderer;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final NutritionistAgentProperties props;

    public MealPlanner(Coordinator coordinator,
                       DietProfileClient profiles,
                       MealReadClient meals,
                       WebSearchClient web,
                       MediaStoreClient media,
                       OrchestratorInvokeClient orchestrator,
                       DocRenderer renderer,
                       SkillRegistry skills,
                       AgentManifest manifest,
                       ObjectMapper json,
                       NutritionistAgentProperties props) {
        this.coordinator = coordinator;
        this.profiles = profiles;
        this.meals = meals;
        this.web = web;
        this.media = media;
        this.orchestrator = orchestrator;
        this.renderer = renderer;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.props = props;
    }

    public Mono<IntentResponse> plan(NormalizedMessage msg) {
        String season = currentSeason();

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("profile", profiles.get(msg.householdId(), msg.userId())
                .map(p -> (JsonNode) json.valueToTree(p)));
        gather.put("householdProfile", profiles.get(msg.householdId(), null)
                .map(p -> (JsonNode) json.valueToTree(p)));
        gather.put("meals", meals.listMeals(msg.householdId(), msg.userId(), MEAL_LIMIT)
                .filter(m -> !m.isEmpty())
                .map(m -> (JsonNode) json.valueToTree(m)));
        String store = namedStore(msg.text());
        if (store != null) {
            gather.put("store", web.search(storeQuery(store, season), STORE_HITS)
                    .map(r -> (JsonNode) json.valueToTree(r)));
        }

        ObjectNode payload = json.createObjectNode();
        if (msg.text() != null && !msg.text().isBlank()) payload.put("request", msg.text());
        payload.put("season", season);

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(result -> store(msg, result.text())
                        .flatMap(link -> recipesFor(msg, result.text())
                                .map(recipes -> reply(summary(result.text())
                                        + "\n\nРацион и список покупок: " + link + recipes, result.llmModel())))
                        .onErrorResume(e -> {
                            log.warn("ration render/store failed: {}", e.toString());
                            // Still hand back the textual plan if the page couldn't be stored.
                            return Mono.just(reply(result.text(), result.llmModel()));
                        }))
                .onErrorResume(e -> {
                    log.warn("meal planning failed: {}", e.toString());
                    return Mono.just(reply(
                            "Не получилось составить рацион. Попробуйте позже.", null));
                });
    }

    /**
     * Ask the chef (CH-b2) for recipes for the ration over the orchestrator hub, returning a "\n\n
     * Рецепты от шефа: &lt;link&gt;" suffix when the chef produced a card, or "" otherwise. Soft-failed —
     * a chef outage / no link just omits the recipes line; the ration is already in hand.
     */
    private Mono<String> recipesFor(NormalizedMessage msg, String rationText) {
        JsonNode args = json.createObjectNode().put("request", rationText);
        var request = new AgentActionRequest(
                "chef", "recommend_recipes", msg.householdId(), msg.userId(), "nutritionist", args);
        return orchestrator.invoke(request)
                .filter(AgentActionResult::ok)
                .map(AgentActionResult::result)
                .map(r -> r != null && r.hasNonNull("link")
                        ? "\n\nРецепты от шефа: " + r.get("link").asText() : "")
                .defaultIfEmpty("")
                .onErrorResume(e -> {
                    log.warn("chef recipes invoke failed (dropping it): {}", e.toString());
                    return Mono.just("");
                });
    }

    /** Render the ration board, store it in media-service, return the public link. */
    private Mono<String> store(NormalizedMessage msg, String planText) {
        Doc doc = Doc.builder("Рацион и список покупок")
                .kicker("Питание · Семья · Закупка")
                .subtitle("План на основе профилей и недавнего рациона")
                .section("План", splitParagraphs(planText))
                .build();
        RenderedDoc rendered = renderer.render(doc);
        return media.upload(msg.householdId(), msg.userId(),
                        rendered.filename(), rendered.mimeType(), rendered.content())
                .map(this::link);
    }

    private String link(MediaObjectDto stored) {
        return base() + "/v1/media/" + stored.id();
    }

    private String base() {
        String base = props.getPublicMediaBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** The first store name mentioned in the request (lower-cased contains), else null. */
    private static String namedStore(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.toLowerCase(Locale.ROOT);
        return STORES.stream().filter(t::contains).findFirst().orElse(null);
    }

    private static String storeQuery(String store, String season) {
        return "ассортимент продукты " + store + " " + season;
    }

    /** Northern-hemisphere season by month (RU label) — a deterministic payload hint, no model needed. */
    private static String currentSeason() {
        int month = LocalDate.now(ZoneOffset.UTC).getMonthValue();
        return switch (month) {
            case 12, 1, 2 -> "зима";
            case 3, 4, 5 -> "весна";
            case 6, 7, 8 -> "лето";
            default -> "осень";
        };
    }

    /** First line of the plan as the chat summary; the full version lives in the HTML. */
    private static String summary(String planText) {
        if (planText == null || planText.isBlank()) {
            return "Составил рацион и список покупок.";
        }
        String firstLine = planText.strip().split("\\R", 2)[0];
        return firstLine.length() > 280 ? firstLine.substring(0, 277) + "…" : firstLine;
    }

    private static List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.strip().split("\\R")) {
            if (!line.isBlank()) out.add(line.strip());
        }
        if (out.isEmpty()) out.add(text.strip());
        return out;
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "meal-planner SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
