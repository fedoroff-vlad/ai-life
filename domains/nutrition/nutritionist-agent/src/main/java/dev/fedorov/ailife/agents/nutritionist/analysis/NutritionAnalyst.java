package dev.fedorov.ailife.agents.nutritionist.analysis;

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
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
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

/**
 * The nutrition-analysis flow (NU-e) — the nutritionist's "how am I eating?" board. On an analysis
 * request it gathers, in parallel on the shared {@link Coordinator}: the person's <b>recent meals</b>
 * (via {@code mcp-nutrition}'s {@code /internal/meals}) and their <b>diet profile</b> (goals /
 * restrictions). The Coordinator folds the successful sources into a {@code context} and runs one LLM
 * synthesis from {@code [AGENT.md, nutrition-analyst SKILL.md] + {payload(request), context}} →
 * intake vs goals, deficits/excesses, recommendations — which is rendered as an HTML board through
 * the shared {@link DocRenderer} seam ({@code libs/doc-render}), stored in media-service, and returned
 * as a link.
 *
 * <p>Per-step soft-fail (no profile just drops that constraint — the analysis stays descriptive). No
 * meals logged yet → an invite to log first (no LLM call). Synthesis failure → a friendly message.
 */
@Component
public class NutritionAnalyst {

    private static final Logger log = LoggerFactory.getLogger(NutritionAnalyst.class);
    private static final String SKILL_NAME = "nutrition-analyst";
    private static final int MEAL_LIMIT = 30;

    private final Coordinator coordinator;
    private final MealReadClient meals;
    private final DietProfileClient profiles;
    private final MediaStoreClient media;
    private final DocRenderer renderer;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final NutritionistAgentProperties props;

    public NutritionAnalyst(Coordinator coordinator,
                            MealReadClient meals,
                            DietProfileClient profiles,
                            MediaStoreClient media,
                            DocRenderer renderer,
                            SkillRegistry skills,
                            AgentManifest manifest,
                            ObjectMapper json,
                            NutritionistAgentProperties props) {
        this.coordinator = coordinator;
        this.meals = meals;
        this.profiles = profiles;
        this.media = media;
        this.renderer = renderer;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.props = props;
    }

    public Mono<IntentResponse> analyse(NormalizedMessage msg) {
        return meals.listMeals(msg.householdId(), msg.userId(), MEAL_LIMIT)
                .onErrorReturn(List.of())
                .flatMap(recent -> {
                    if (recent.isEmpty()) {
                        return Mono.just(reply(
                                "В дневнике питания пока пусто. Запишите несколько приёмов пищи "
                                        + "(текстом или фото) — и я сделаю разбор питания.", null));
                    }
                    return synthesize(msg, recent);
                })
                .onErrorResume(e -> {
                    log.warn("nutrition analysis failed: {}", e.toString());
                    return Mono.just(reply("Не получилось сделать разбор питания. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> synthesize(NormalizedMessage msg, List<MealLogDto> recent) {
        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("meals", Mono.just(json.valueToTree(recent)));
        gather.put("profile", profiles.get(msg.householdId(), msg.userId())
                .map(p -> (JsonNode) json.valueToTree(p)));

        ObjectNode payload = json.createObjectNode();
        if (msg.text() != null && !msg.text().isBlank()) payload.put("request", msg.text());
        payload.put("mealCount", recent.size());

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(result -> store(msg, result.text())
                        .map(link -> reply(summary(result.text()) + "\n\nПолный разбор: " + link, result.llmModel()))
                        .onErrorResume(e -> {
                            log.warn("analysis render/store failed: {}", e.toString());
                            // Still hand back the textual analysis if the page couldn't be stored.
                            return Mono.just(reply(result.text(), result.llmModel()));
                        }));
    }

    /** Render the analysis board, store it in media-service, return the public link. */
    private Mono<String> store(NormalizedMessage msg, String analysisText) {
        Doc doc = Doc.builder("Разбор питания")
                .kicker("Питание · Баланс · Цели")
                .subtitle("На основе недавних приёмов пищи")
                .section("Анализ", splitParagraphs(analysisText))
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

    /** First line of the synthesis as the chat summary; the full version lives in the HTML. */
    private static String summary(String analysisText) {
        if (analysisText == null || analysisText.isBlank()) {
            return "Сделал разбор вашего питания.";
        }
        String firstLine = analysisText.strip().split("\\R", 2)[0];
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
                        "nutrition-analyst SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
