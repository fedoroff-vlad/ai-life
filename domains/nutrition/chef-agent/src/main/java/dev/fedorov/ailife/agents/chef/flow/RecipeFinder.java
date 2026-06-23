package dev.fedorov.ailife.agents.chef.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.chef.config.ChefAgentProperties;
import dev.fedorov.ailife.agents.chef.http.MediaStoreClient;
import dev.fedorov.ailife.agents.chef.http.WebSearchClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.docrender.DocRenderer;
import dev.fedorov.ailife.docrender.RenderedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The recipe flow (CH-b) — the chef's reason for existing. On a recipe request it (1) <b>searches</b>
 * the web for recipes via {@code mcp-web} {@code /internal/search} (biased to recipe sources like
 * food.ru), then (2) folds the hits into a {@code context} and runs <b>one</b> LLM synthesis on the
 * shared {@link Coordinator} from {@code [AGENT.md, recipe-finder SKILL.md] + {payload(request),
 * context}} → a short recipe card (which dishes to cook + how), which is rendered as an HTML card —
 * the synthesized text as sections plus the <b>real recipe links from the search hits</b> (grounded,
 * never LLM-invented URLs) — through the shared {@link DocRenderer}, stored in media-service, and
 * returned as a link.
 *
 * <p><b>Token economy is structural:</b> the search is plain HTTP (no model cost); only the synthesis
 * hits the LLM. Empty search → the skill falls back to a couple of simple dishes (no links). Every
 * stage degrades to a friendly message on failure.
 *
 * <p>The core {@link #recommend} serves both entry points: the direct intent path (CH-b1, wrapped as
 * an {@link IntentResponse}) and the inter-agent action (CH-b2) the nutritionist's ration flow (NU-g)
 * invokes over the orchestrator hub (ration → recipes), wrapped as an {@code AgentActionResult}.
 */
@Component
public class RecipeFinder {

    private static final Logger log = LoggerFactory.getLogger(RecipeFinder.class);
    private static final String SKILL_NAME = "recipe-finder";
    private static final int SEARCH_LIMIT = 6;
    private static final int MAX_LINKS = 6;
    private static final int MAX_QUERY = 120;

    private final Coordinator coordinator;
    private final WebSearchClient web;
    private final MediaStoreClient media;
    private final DocRenderer renderer;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final ChefAgentProperties props;

    public RecipeFinder(Coordinator coordinator,
                        WebSearchClient web,
                        MediaStoreClient media,
                        DocRenderer renderer,
                        SkillRegistry skills,
                        AgentManifest manifest,
                        ObjectMapper json,
                        ChefAgentProperties props) {
        this.coordinator = coordinator;
        this.web = web;
        this.media = media;
        this.renderer = renderer;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.props = props;
    }

    /** Direct intent path (CH-b1): a recipe-cue message → a recipe card link in the reply. */
    public Mono<IntentResponse> findRecipes(NormalizedMessage msg) {
        String request = msg == null ? null : msg.text();
        if (request == null || request.isBlank()) {
            return Mono.just(reply("Что приготовить? Назовите блюдо или продукты — подберу рецепты.", null));
        }
        return recommend(msg.householdId(), msg.userId(), request)
                .map(o -> reply(o.link() == null ? o.fullText() : o.summary() + "\n\nРецепты: " + o.link(),
                        o.model()));
    }

    /**
     * The core search → synthesize → render → store, returning a {@link RecipeOutcome}. Shared by the
     * intent path and the inter-agent action (CH-b2). Never errors — a failed stage yields an outcome
     * with a friendly message and a null link.
     */
    public Mono<RecipeOutcome> recommend(UUID householdId, UUID userId, String request) {
        if (request == null || request.isBlank()) {
            return Mono.just(new RecipeOutcome("Не указан запрос на рецепты.", null, null, null));
        }
        return web.search(recipeQuery(request), SEARCH_LIMIT)
                .onErrorResume(e -> {
                    log.warn("recipe search failed for '{}': {}", request, e.toString());
                    return Mono.just(new WebSearchResult(request, List.of()));
                })
                .flatMap(result -> synthesize(householdId, userId, request, hits(result)))
                .onErrorResume(e -> {
                    log.warn("recipe flow failed: {}", e.toString());
                    return Mono.just(new RecipeOutcome(
                            "Не получилось подобрать рецепты. Попробуйте позже.", null, null, null));
                });
    }

    private Mono<RecipeOutcome> synthesize(UUID householdId, UUID userId, String request, List<WebSearchHit> hits) {
        ObjectNode corpus = json.createObjectNode();
        corpus.put("query", request);
        ArrayNode sources = corpus.putArray("sources");
        for (WebSearchHit h : hits) {
            ObjectNode s = sources.addObject();
            if (h.title() != null) s.put("title", h.title());
            s.put("url", h.url());
            if (h.snippet() != null) s.put("snippet", h.snippet());
        }

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("web", Mono.just(corpus));

        ObjectNode payload = json.createObjectNode();
        payload.put("request", request);

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(result -> store(householdId, userId, result.text(), hits)
                        .map(link -> new RecipeOutcome(summary(result.text()), result.text(), link, result.llmModel()))
                        .onErrorResume(e -> {
                            log.warn("recipe render/store failed: {}", e.toString());
                            // Still hand back the textual card if the page couldn't be stored.
                            return Mono.just(new RecipeOutcome(
                                    summary(result.text()), result.text(), null, result.llmModel()));
                        }));
    }

    /** Render the recipe card (the synthesized text + the real recipe links), store, link. */
    private Mono<String> store(UUID householdId, UUID userId, String cardText, List<WebSearchHit> hits) {
        Doc.Builder b = Doc.builder("Рецепты")
                .kicker("Меню · Рецепты · Кухня")
                .subtitle("Подобрано под ваш запрос")
                .section("Что приготовить", splitParagraphs(cardText));
        int added = 0;
        for (WebSearchHit h : hits) {
            if (h.url() == null || h.url().isBlank()) continue;
            b.link(h.title() == null || h.title().isBlank() ? h.url() : h.title(), h.url(), h.snippet());
            if (++added >= MAX_LINKS) break;
        }
        RenderedDoc rendered = renderer.render(b.build());
        return media.upload(householdId, userId,
                        rendered.filename(), rendered.mimeType(), rendered.content())
                .map(this::link);
    }

    private static List<WebSearchHit> hits(WebSearchResult result) {
        return result == null || result.hits() == null ? List.of() : result.hits();
    }

    /**
     * Bias the query toward recipe sources (food.ru and similar) without hard-coding one site, and
     * cap it to the first line / {@value #MAX_QUERY} chars — a long ration (the CH-b2 action input)
     * makes a poor search query, so we use its headline.
     */
    private static String recipeQuery(String request) {
        String head = request.strip().split("\\R", 2)[0].strip();
        if (head.length() > MAX_QUERY) head = head.substring(0, MAX_QUERY);
        return head + " рецепт";
    }

    private String link(MediaObjectDto stored) {
        return base() + "/v1/media/" + stored.id();
    }

    private String base() {
        String base = props.getPublicMediaBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** First line of the card as the chat summary; the full version lives in the HTML. */
    private static String summary(String cardText) {
        if (cardText == null || cardText.isBlank()) {
            return "Подобрал рецепты под ваш запрос.";
        }
        String firstLine = cardText.strip().split("\\R", 2)[0];
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
                        "recipe-finder SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /**
     * The outcome of a recipe run: a short {@code summary} (chat headline), the full {@code fullText}
     * card, the deliverable {@code link} (null if the page couldn't be stored), and the model.
     */
    public record RecipeOutcome(String summary, String fullText, String link, String model) {
    }
}
