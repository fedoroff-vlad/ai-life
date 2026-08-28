package dev.fedorov.ailife.agents.nutritionist.analysis;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.transparency.DegradedNotice;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.nutritionist.http.DietProfileClient;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.profile.ProfileScopeResolver;
import dev.fedorov.ailife.agents.nutritionist.http.MealReadClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
import dev.fedorov.ailife.docrender.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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
    private final ProfileScopeResolver profileScope;
    private final DeliverablePublisher publisher;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public NutritionAnalyst(Coordinator coordinator,
                            MealReadClient meals,
                            DietProfileClient profiles,
                            ProfileScopeResolver profileScope,
                            DeliverablePublisher publisher,
                            SkillRegistry skills,
                            AgentManifest manifest,
                            ObjectMapper json) {
        this.coordinator = coordinator;
        this.meals = meals;
        this.profiles = profiles;
        this.profileScope = profileScope;
        this.publisher = publisher;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
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
        // Resolve the requester's diet profile via the shared rule (ADR-0005): self → own
        // household-default → family/shared household-default → empty (#490 FO-3 now inherited, so a
        // member who set nothing still gets the household's goals in their analysis).
        gather.put("profile", profileScope.<DietProfileDto>resolve(
                        msg.userId(), msg.householdId(), profiles::get)
                .map(p -> json.valueToTree(p)));

        ObjectNode payload = json.createObjectNode();
        if (msg.text() != null && !msg.text().isBlank()) payload.put("request", msg.text());
        payload.put("mealCount", recent.size());

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(result -> store(msg, result.text())
                        .map(link -> reply(DeliverablePublisher.summary(result.text(), "Сделал разбор вашего питания.")
                                + "\n\nПолный разбор: " + link, result.llmModel()))
                        .onErrorResume(e -> {
                            log.warn("analysis render/store failed: {}", e.toString());
                            // Still hand back the textual analysis, but say the board is missing rather
                            // than pretend a full delivery (#485, no silent failures).
                            return Mono.just(reply(DegradedNotice.append(result.text(),
                                    "не смог собрать HTML-доску сейчас — прислал разбор только текстом, попробуйте позже"),
                                    result.llmModel()));
                        }));
    }

    /** Render the analysis board, store it in media-service, return the public link. */
    private Mono<String> store(NormalizedMessage msg, String analysisText) {
        Doc doc = Doc.builder("Разбор питания")
                .kicker("Питание · Баланс · Цели")
                .subtitle("На основе недавних приёмов пищи")
                .section("Анализ", DeliverablePublisher.splitParagraphs(analysisText))
                .build();
        return publisher.publish(msg.householdId(), msg.userId(), doc);
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
