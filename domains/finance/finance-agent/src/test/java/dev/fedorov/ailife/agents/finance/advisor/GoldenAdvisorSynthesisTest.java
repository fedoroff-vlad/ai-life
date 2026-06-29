package dev.fedorov.ailife.agents.finance.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillParser;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.SpendingClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises the finance {@code financial-advisor} <b>synthesis
 * skill</b> against a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway),
 * asserting <b>structure, not text</b> (roadmap §Risks). Complements the researcher synthesis golden
 * test (link provenance) by covering the issue's other synthesis concern — <i>"a synthesis contains
 * its required content, grounded in the data"</i>: given two fixed spend-by-category windows, the real
 * model must write an analysis that names the actual top category, shows the actual currency, and
 * spans multiple real categories — never an analysis hallucinated from thin air.
 *
 * <p><b>Opt-in / gated.</b> Skipped unless {@code GOLDEN_LLM} is set (CI default = unset). To run it:
 * <pre>
 *   # 1. local Ollama with qwen2.5:7b pulled (see project memory / llm-gateway README)
 *   # 2. a llm-gateway pointed at it (raise the timeout — synthesis is a long generation):
 *   LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
 *   LLM_DEFAULT_MODEL=qwen2.5:7b LLM_REQUEST_TIMEOUT_SECONDS=180 LLM_GATEWAY_PORT=8081 \
 *     mvn -q -pl platform/llm-gateway spring-boot:run
 *   # 3. the test, pointed at the gateway:
 *   GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
 *     mvn -q -pl domains/finance/finance-agent -Dtest=GoldenAdvisorSynthesisTest test
 * </pre>
 *
 * <p>{@link SpendingClient} is mocked to supply the two windows; the real {@link Coordinator} runs the
 * one synthesis hop over the real AGENT.md + financial-advisor SKILL.md. We assert the analysis grounds
 * in the supplied figures (top category, currency, breadth), never wording.
 */
@Tag("golden")
@EnabledIfEnvironmentVariable(named = "GOLDEN_LLM", matches = "(?i)1|true|yes|on")
class GoldenAdvisorSynthesisTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = new LlmClient(WebClient.builder().baseUrl(gatewayUrl()).build());
    private final Coordinator coordinator = new Coordinator(llm, json);
    private final SpendingClient spending = mock(SpendingClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "finance", "finance agent", "0.1.0", 8093,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(), agentBody());
    private final SkillRegistry skills = new SkillRegistry(List.of(loadSkill()));
    private final FinancialAdvisor advisor =
            new FinancialAdvisor(coordinator, spending, skills, manifest, json);

    // The recent window — the top category leads; currency is consistent so amounts are comparable.
    private static final String TOP = "Продукты";
    private static final String SECOND = "Кафе и рестораны";
    private static final String THIRD = "Транспорт";
    private static final String CURRENCY = "EUR";

    /**
     * STRUCTURE — the real model, given the real advisor prompt and two concrete spend windows, must
     * write a non-trivial analysis grounded in the data: it names the actual top category, shows the
     * actual currency, and references at least two of the real categories. This is the "required,
     * grounded synthesis" assertion for a free-text skill — it checks data provenance, never wording.
     */
    @Test
    void analysisIsGroundedInTheSpendingData() {
        UUID household = UUID.randomUUID();
        Instant now = Instant.now();
        // recent window: arg `to` is ~now; previous window: `to` is ~90 days back. Branch on it.
        when(spending.spendingByCategory(eq(household), any(Instant.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    Instant to = inv.getArgument(2);
                    boolean recent = to.isAfter(now.minus(Duration.ofDays(1)));
                    return Mono.just(recent ? recentRows() : previousRows());
                });

        FinancialAdvisor.AdviceResult r = advisor.advise(message(household,
                "проанализируй мои траты за последнее время")).block(Duration.ofSeconds(150));

        assertThat(r).as("null result — is llm-gateway up at %s?", gatewayUrl()).isNotNull();
        String text = r.text();
        assertThat(text).as("empty analysis").isNotBlank();
        assertThat(text.length()).as("analysis is implausibly short: %s", text).isGreaterThan(100);

        assertThat(text)
                .as("analysis did not name the top category «%s»:\n%s", TOP, text)
                .contains(TOP);
        // Skill rule: always show the currency. Accept either the ISO code or the symbol — both
        // satisfy "amounts with their currency"; pinning the exact form would be a text assertion.
        assertThat(text.contains(CURRENCY) || text.contains("€"))
                .as("analysis did not show the currency (EUR/€, skill rule):\n%s", text)
                .isTrue();
        long categoriesMentioned = List.of(TOP, SECOND, THIRD).stream()
                .filter(text::contains).count();
        assertThat(categoriesMentioned)
                .as("analysis referenced fewer than two real categories (not grounded in the data):\n%s", text)
                .isGreaterThanOrEqualTo(2);
    }

    private static List<SpendingByCategoryRow> recentRows() {
        return List.of(
                row(TOP, new BigDecimal("540.00"), 42),
                row(SECOND, new BigDecimal("320.00"), 18),
                row(THIRD, new BigDecimal("150.00"), 25));
    }

    private static List<SpendingByCategoryRow> previousRows() {
        return List.of(
                row(TOP, new BigDecimal("480.00"), 38),
                row(SECOND, new BigDecimal("210.00"), 12),
                row(THIRD, new BigDecimal("180.00"), 30));
    }

    private static SpendingByCategoryRow row(String name, BigDecimal spent, long txCount) {
        return new SpendingByCategoryRow(UUID.randomUUID(), name, CURRENCY, spent, txCount);
    }

    private static NormalizedMessage message(UUID household, String text) {
        return new NormalizedMessage(UUID.randomUUID(), household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "golden", Instant.now());
    }

    /** The real financial-advisor SKILL.md, packaged on the classpath at skills/finance/<name>/SKILL.md. */
    private static Skill loadSkill() {
        try (InputStream in = GoldenAdvisorSynthesisTest.class.getClassLoader()
                .getResourceAsStream("skills/finance/financial-advisor/SKILL.md")) {
            if (in == null) {
                throw new IllegalStateException("financial-advisor SKILL.md not on the test classpath");
            }
            return SkillParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load financial-advisor SKILL.md", e);
        }
    }

    /** The real finance system prompt — AGENT.md body (frontmatter stripped), off the classpath. */
    private static String agentBody() {
        try (InputStream in = GoldenAdvisorSynthesisTest.class.getClassLoader().getResourceAsStream("AGENT.md")) {
            if (in == null) return "You are the finance agent for the ai-life system.";
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
            return "You are the finance agent for the ai-life system.";
        }
    }

    private static String gatewayUrl() {
        String url = System.getenv("GOLDEN_LLM_GATEWAY_URL");
        return (url == null || url.isBlank()) ? "http://localhost:8081" : url.trim();
    }
}
