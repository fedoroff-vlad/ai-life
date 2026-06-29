package dev.fedorov.ailife.agents.nutritionist.foodlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillParser;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.nutritionist.http.CaptionClient;
import dev.fedorov.ailife.agents.nutritionist.http.MealClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.nutrition.LogMealInput;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises the nutritionist {@code meal-logger} <b>JSON-extract
 * skill</b> against a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway),
 * asserting <b>structure, not text</b> (roadmap §Risks). A third agent on the same surface the
 * inbox-clarify golden test covers (a skill that must return strict JSON): given a typed meal, the real
 * model must emit a {@code {"description":…, kcal, protein_g, …}} object that the production
 * {@link FoodLogger} parses into a {@link LogMealInput} — a real description plus structured macros,
 * not prose.
 *
 * <p><b>Opt-in / gated.</b> Skipped unless {@code GOLDEN_LLM} is set (CI default = unset). To run it:
 * <pre>
 *   # 1. local Ollama with qwen2.5:7b pulled (see project memory / llm-gateway README)
 *   # 2. a llm-gateway pointed at it:
 *   LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
 *   LLM_DEFAULT_MODEL=qwen2.5:7b LLM_REQUEST_TIMEOUT_SECONDS=180 LLM_GATEWAY_PORT=8081 \
 *     mvn -q -pl platform/llm-gateway spring-boot:run
 *   # 3. the test, pointed at the gateway:
 *   GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
 *     mvn -q -pl domains/nutrition/nutritionist-agent -Dtest=GoldenMealLogTest test
 * </pre>
 *
 * <p>{@link CaptionClient} (unused on the typed path) and {@link MealClient} (the write) are mocked; we
 * capture the {@link LogMealInput} the production parser built from the real model's reply and assert
 * its structure, never the wording.
 */
@Tag("golden")
@EnabledIfEnvironmentVariable(named = "GOLDEN_LLM", matches = "(?i)1|true|yes|on")
class GoldenMealLogTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = new LlmClient(WebClient.builder().baseUrl(gatewayUrl()).build());
    private final CaptionClient caption = mock(CaptionClient.class);
    private final MealClient meals = mock(MealClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "nutritionist", "nutritionist agent", "0.1.0", 8105,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(), agentBody());
    private final SkillRegistry skills = new SkillRegistry(List.of(loadSkill()));
    private final FoodLogger logger = new FoodLogger(caption, meals, llm, skills, manifest, json);

    /**
     * STRUCTURE — the real model, given the real meal-logger prompt and a concrete meal, must emit
     * strict JSON the production parser turns into a {@link LogMealInput}: a non-blank description plus
     * at least one structured signal (identified items or an estimated kcal). This is the "parseable,
     * contract-shaped output" assertion — it checks the extract's shape, never the wording.
     */
    @Test
    void extractsAStructuredMealEntry() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        // The write echoes its input back as a saved row — we capture the input the parser built.
        ArgumentCaptor<LogMealInput> captor = ArgumentCaptor.forClass(LogMealInput.class);
        when(meals.log(any(LogMealInput.class))).thenAnswer(inv -> {
            LogMealInput in = inv.getArgument(0);
            return Mono.just(new MealLogDto(
                    UUID.randomUUID(), in.householdId(), in.ownerId(), in.eatenAt(), in.source(),
                    in.description(), in.items(), in.kcal(), in.proteinG(), in.fatG(), in.carbsG(),
                    in.imageMediaId(), Instant.now()));
        });

        var msg = new NormalizedMessage(user, household, MessageScope.PRIVATE,
                "на обед съел куриный салат с овощами и кусок хлеба, порция большая",
                List.of(), "telegram", "golden", Instant.now());

        var resp = logger.logText(msg).block(Duration.ofSeconds(120));
        assertThat(resp).as("null result — is llm-gateway up at %s?", gatewayUrl()).isNotNull();

        // Reaching the write means the model produced parseable JSON with a usable description.
        verify(meals, times(1)).log(captor.capture());
        LogMealInput logged = captor.getValue();
        assertThat(logged.description())
                .as("model produced no usable meal description (degraded reply: %s)", resp.text())
                .isNotBlank();
        boolean hasItems = logged.items() != null && logged.items().isArray() && !logged.items().isEmpty();
        boolean hasKcal = logged.kcal() != null;
        assertThat(hasItems || hasKcal)
                .as("extract had neither identified items nor an estimated kcal — not a structured entry: %s", logged)
                .isTrue();
        // Macros, when present, are numeric by construction (the parser only keeps numeric fields);
        // a present kcal must be a sane positive estimate, never zero/negative.
        if (hasKcal) {
            assertThat(logged.kcal()).as("non-positive kcal estimate: %s", logged).isPositive();
        }
    }

    /** The real meal-logger SKILL.md, packaged on the classpath at skills/nutrition/<name>/SKILL.md. */
    private static Skill loadSkill() {
        try (InputStream in = GoldenMealLogTest.class.getClassLoader()
                .getResourceAsStream("skills/nutrition/meal-logger/SKILL.md")) {
            if (in == null) {
                throw new IllegalStateException("meal-logger SKILL.md not on the test classpath");
            }
            return SkillParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load meal-logger SKILL.md", e);
        }
    }

    /** The real nutritionist system prompt — AGENT.md body (frontmatter stripped), off the classpath. */
    private static String agentBody() {
        try (InputStream in = GoldenMealLogTest.class.getClassLoader().getResourceAsStream("AGENT.md")) {
            if (in == null) return "You are the nutritionist agent for the ai-life system.";
            String md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (md.startsWith("---")) {
                int close = md.indexOf("\n---", 3);
                if (close >= 0) {
                    int bodyStart = md.indexOf('\n', close + 1);
                    if (bodyStart >= 0) return md.substring(bodyStart + 1).strip();
                }
            }
            return md.strip();
        } catch (Exception e) {
            return "You are the nutritionist agent for the ai-life system.";
        }
    }

    private static String gatewayUrl() {
        String url = System.getenv("GOLDEN_LLM_GATEWAY_URL");
        return (url == null || url.isBlank()) ? "http://localhost:8081" : url.trim();
    }
}
