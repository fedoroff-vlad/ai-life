package dev.fedorov.ailife.agents.finance.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.SpendingClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
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
@GoldenLlmTest
class GoldenAdvisorSynthesisTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final Coordinator coordinator = new Coordinator(llm, json);
    private final SpendingClient spending = mock(SpendingClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "finance", "finance agent", "0.1.0", 8093,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenAdvisorSynthesisTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenAdvisorSynthesisTest.class.getClassLoader(), "skills/finance/financial-advisor/SKILL.md")));
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

        FinancialAdvisor.AdviceResult r = advisor.advise(GoldenLlm.message(household,
                "проанализируй мои траты за последнее время")).block(Duration.ofSeconds(150));

        assertThat(r).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
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

}
