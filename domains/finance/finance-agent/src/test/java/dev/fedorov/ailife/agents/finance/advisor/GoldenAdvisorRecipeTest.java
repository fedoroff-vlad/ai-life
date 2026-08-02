package dev.fedorov.ailife.agents.finance.advisor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 <b>golden test</b> for the <b>skills-vs-flows Bucket 2 pilot</b> (#360) — the "validate-only"
 * half. It exercises the <b>executable-recipe</b> form of the {@code financial-advisor} flow
 * ({@code recipes/financial-advisor.recipe.md}, a test fixture — NOT loaded in production) against a
 * <b>real model</b>, asserting the one thing the recipe adds over the existing synthesis golden: the
 * model can <b>plan the gather itself</b> from the recipe, instead of the plan being hard-coded in the
 * Java {@link FinancialAdvisor}.
 *
 * <p>Given the recipe (which exposes a single generic tool, {@code spending_by_category}) and a spending
 * request, the model must emit a parseable <b>gather plan</b> — the trend-comparison windows the analysis
 * needs — grounded in the one tool it was given, inventing no others. Synthesis itself stays covered by
 * {@link GoldenAdvisorSynthesisTest}; together the two goldens prove the recipe is correct end-to-end,
 * which is exactly what the pilot must show before the (model-gated) production cutover.
 *
 * <p><b>Structure, not text</b> (roadmap §Risks): we assert the plan's shape and tool-grounding, never
 * its wording. <b>Opt-in / gated</b>: skipped unless {@code GOLDEN_LLM} is set (CI default = unset). Run
 * it against a real model — local Ollama or the {@code openai:}-tier work gateway (#359):
 * <pre>
 *   scripts/golden.sh -pl domains/finance/finance-agent -Dtest=GoldenAdvisorRecipeTest
 *   GOLDEN_PROFILE=openai scripts/golden.sh -pl domains/finance/finance-agent -Dtest=GoldenAdvisorRecipeTest
 * </pre>
 * See {@code platform/llm-gateway/README.md} §Golden tests.
 */
@GoldenLlmTest
class GoldenAdvisorRecipeTest {

    private static final String RECIPE = "recipes/financial-advisor.recipe.md";
    private static final String ONLY_TOOL = "spending_by_category";

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();

    /**
     * STRUCTURE — the real model, given the recipe and a spending request, plans the gather itself: it
     * returns a parseable {@code gather} plan that (a) uses ONLY the one tool the recipe exposes, (b)
     * includes a recent window ending now, and (c) spans at least two windows (the trend comparison the
     * analysis requires). This is the capability the Java flow hard-codes today — proven model-driven.
     */
    @Test
    void modelPlansTheGatherFromTheRecipe() {
        String recipe = load(RECIPE);

        List<LlmMessage> messages = List.of(
                LlmMessage.system(recipe),
                LlmMessage.user("проанализируй мои траты за последнее время.\n\n"
                        + "Return ONLY the Step 1 JSON gather plan (a single object with a \"gather\" "
                        + "array). Do not write the analysis yet."));

        LlmChatResponse resp = llm.chat(LlmChatRequest.of(LlmChannel.DEFAULT, messages))
                .block(Duration.ofSeconds(150));

        assertThat(resp).as("null response — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        String content = resp.content();
        assertThat(content).as("empty plan").isNotBlank();

        JsonNode plan = parseJsonObject(content);
        assertThat(plan).as("model did not return a JSON object for the gather plan:\n%s", content).isNotNull();

        JsonNode gather = plan.get("gather");
        assertThat(gather != null && gather.isArray())
                .as("plan has no \"gather\" array:\n%s", content).isTrue();

        int windows = 0;
        boolean hasRecent = false;
        for (JsonNode step : gather) {
            windows++;
            // (a) grounded in the ONE tool the recipe exposes — never an invented tool name.
            JsonNode tool = step.get("tool");
            assertThat(tool != null && ONLY_TOOL.equals(tool.asString()))
                    .as("plan step names a tool other than %s (invented tool):\n%s", ONLY_TOOL, content)
                    .isTrue();
            // (b) a recent window ends at "now" (toDaysAgo == 0).
            JsonNode to = step.get("toDaysAgo");
            if (to != null && to.isNumber() && to.asInt() == 0) {
                hasRecent = true;
            }
        }

        assertThat(windows)
                .as("a spending analysis needs a trend comparison — the plan has fewer than two windows:\n%s", content)
                .isGreaterThanOrEqualTo(2);
        assertThat(hasRecent)
                .as("plan has no recent window ending now (toDaysAgo == 0):\n%s", content)
                .isTrue();
    }

    /** Extract the first {@code { … }} object from a model reply and parse it; null if none/unparseable. */
    private JsonNode parseJsonObject(String content) {
        String s = content.strip();
        // Strip a ```json … ``` fence if the model wrapped the JSON in one.
        int firstBrace = s.indexOf('{');
        int lastBrace = s.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            return null;
        }
        try {
            JsonNode node = json.readTree(s.substring(firstBrace, lastBrace + 1));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String load(String resourcePath) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("recipe not on the test classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load recipe: " + resourcePath, e);
        }
    }
}
