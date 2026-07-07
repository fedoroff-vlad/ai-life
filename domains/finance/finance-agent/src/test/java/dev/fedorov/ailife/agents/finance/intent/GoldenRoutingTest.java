package dev.fedorov.ailife.agents.finance.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.advisor.FinancialAdvisor;
import dev.fedorov.ailife.agents.finance.advisor.InvestmentAdvisor;
import dev.fedorov.ailife.agents.finance.report.MonthlyReporter;
import dev.fedorov.ailife.agents.finance.report.YearReporter;
import dev.fedorov.ailife.agents.finance.tools.ToolDispatcher;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises finance-agent's intent routing against a <b>real
 * model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting <b>structure, not
 * text</b> (roadmap §Risks). It validates the single highest-risk surface: the LLM, given the real
 * AGENT.md + the real classifier prompt + the real tool list, must emit parseable routing JSON whose
 * {@code action} is one of the contract values and whose tool {@code name} is a real tool — and route
 * unambiguous requests to the right action.
 *
 * <p><b>Opt-in / gated.</b> Skipped unless {@code GOLDEN_LLM} is set (CI default = unset, so the suite
 * stays green on the mock provider without a model). To run it:
 * <pre>
 *   # 1. a real model — local Ollama with qwen2.5:7b pulled (see project memory / llm-gateway README)
 *   # 2. a llm-gateway pointed at it:
 *   LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
 *   LLM_DEFAULT_MODEL=qwen2.5:7b LLM_GATEWAY_PORT=8081 \
 *     mvn -q -pl platform/llm-gateway -am spring-boot:run
 *   # 3. the test, pointed at the gateway:
 *   GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
 *     mvn -q -pl domains/finance/finance-agent -Dtest=GoldenRoutingTest test
 * </pre>
 *
 * <p>The router's collaborators below the LLM hop ({@code FinancialAdvisor} / {@code InvestmentAdvisor}
 * / {@code MonthlyReporter} / {@code ToolDispatcher}) are mocked — we assert which branch the real
 * model selected ({@code RouterResult.invokedTool}), not those flows' own output.
 */
@GoldenLlmTest
class GoldenRoutingTest {

    /** The contract actions the classifier prompt allows. */
    private static final Set<String> ACTIONS = Set.of("tool", "advice", "report", "invest", "chat");

    /** The mcp-finance tools the dispatcher exposes (must match the canonical tool set). */
    private static final List<ToolDefinition> TOOLS = List.of(
            tool("add_transaction", "Record a personal/household finance transaction (expense negative, income positive)."),
            tool("update_transaction", "Update fields of an existing transaction by id."),
            tool("delete_transaction", "Delete a transaction by id."),
            tool("list_transactions", "List transactions for a household over a time window."),
            tool("get_balance", "Get the current balance of an account."),
            tool("spending_by_category", "Aggregate spending grouped by category over a time window."),
            tool("upsert_category", "Create or update a category (optionally under a parent)."),
            tool("list_categories", "List the household's categories."),
            tool("set_budget", "Create or update a category budget for a period."),
            tool("get_budget_status", "Get spent-vs-limit status for a category budget."),
            tool("upsert_recurring", "Create or update a recurring payment/income line."),
            tool("list_recurring", "List recurring payment/income lines."),
            tool("export_csv", "Export transactions to CSV."));

    private static final Set<String> TOOL_NAMES = TOOLS.stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet());

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final FinancialAdvisor advisor = mock(FinancialAdvisor.class);
    private final InvestmentAdvisor investmentAdvisor = mock(InvestmentAdvisor.class);
    private final MonthlyReporter monthlyReporter = mock(MonthlyReporter.class);
    private final YearReporter yearReporter = mock(YearReporter.class);
    private final AgentManifest manifest = new AgentManifest(
            "finance", "finance agent", "0.1.0", 8093,
            List.of(), List.of(), List.of(), List.of(),
            GoldenLlm.agentBody(GoldenRoutingTest.class.getClassLoader()));
    private final IntentRouter router = new IntentRouter(
            llm, dispatcher, advisor, investmentAdvisor, monthlyReporter, yearReporter, manifest, json);

    GoldenRoutingTest() {
        when(dispatcher.availableToolDefinitions()).thenReturn(TOOLS);
        // A tool dispatch returns the tool's JSON result; stub a non-null body so the tool branch
        // emits a RouterResult (we assert the routing decision, not the tool's real output).
        when(dispatcher.dispatch(anyString(), anyString())).thenReturn("{\"ok\":true}");
        when(advisor.advise(any(NormalizedMessage.class)))
                .thenReturn(Mono.just(new FinancialAdvisor.AdviceResult("(advice)", "qwen2.5:7b")));
        when(investmentAdvisor.advise(any(NormalizedMessage.class), any()))
                .thenReturn(Mono.just(new InvestmentAdvisor.AdviceResult("(invest)", "qwen2.5:7b")));
        when(monthlyReporter.report(any(NormalizedMessage.class)))
                .thenReturn(Mono.just(new MonthlyReporter.ReportResult("(report)", "qwen2.5:7b")));
        when(yearReporter.report(any(NormalizedMessage.class)))
                .thenReturn(Mono.just(new MonthlyReporter.ReportResult("(year-report)", "qwen2.5:7b")));
    }

    /**
     * STRUCTURE — the real model, given the real prompts, must return well-formed routing JSON: a JSON
     * object with an {@code action} in the contract set, and a real tool {@code name} when {@code
     * action=tool}. This is the "structure, not text" assertion — it never checks wording.
     */
    @Test
    void classifierEmitsWellFormedRoutingJson() {
        String prompt = router.buildClassifierPrompt(TOOLS);
        for (String msg : List.of(
                "добавь трату 1500 рублей на еду",
                "какой баланс на моей карте?",
                "сколько я потратил на продукты в этом месяце?",
                "проанализируй мои траты за квартал",
                "сделай финансовый отчёт за месяц",
                "что думаешь про биткоин и золото?",
                "привет, как дела?")) {
            String raw = chat(prompt, msg);
            JsonNode node = extractJson(raw);
            if (node == null) {
                fail("Not parseable JSON for «%s» — raw model output was:\n%s".formatted(msg, raw));
            }
            assertThat(node.hasNonNull("action"))
                    .as("missing 'action' for «%s»: %s", msg, raw).isTrue();
            String action = node.get("action").asText();
            // The accepted contract: a control action, OR a flattened tool name (the two-level shape
            // {action:tool,name:X} collapsed to {action:X}) — the router tolerates both (see IntentRouter
            // format-drift handling). Either way the resolved action/tool must be a real, known value.
            boolean controlAction = ACTIONS.contains(action);
            boolean flattenedTool = TOOL_NAMES.contains(action);
            assertThat(controlAction || flattenedTool)
                    .as("action '%s' is neither a control action nor a known tool, for «%s»: %s", action, msg, raw)
                    .isTrue();
            if ("tool".equals(action)) {
                assertThat(node.hasNonNull("name"))
                        .as("action=tool without 'name' for «%s»: %s", msg, raw).isTrue();
                assertThat(TOOL_NAMES)
                        .as("hallucinated tool '%s' for «%s»", node.get("name").asText(), msg)
                        .contains(node.get("name").asText());
            }
        }
    }

    /**
     * BEHAVIOUR — unambiguous requests must reach the right branch end-to-end through {@link
     * IntentRouter#route}. A softer signal than the structure test (a 7B can mis-route a borderline
     * phrasing), so the cases here are deliberately crisp.
     */
    @Test
    void routesUnambiguousRequestsToTheRightAction() {
        assertRoutesTo("запиши расход 1500 рублей на продукты", "add_transaction");
        assertRoutesTo("какой сейчас баланс на карте?", "get_balance");
        assertRoutesTo("проанализируй мои траты и подскажи где сэкономить", "advice");
        assertRoutesTo("сделай финансовый отчёт за месяц", "report");
        assertRoutesTo("стоит ли смотреть на акции Apple?", "invest");
    }

    private void assertRoutesTo(String text, String expectedInvokedTool) {
        IntentRouter.RouterResult r = router.route(GoldenLlm.message(text)).block(Duration.ofSeconds(60));
        assertThat(r).as("null result for «%s»", text).isNotNull();
        assertThat(r.invokedTool())
                .as("«%s» should route to '%s' but went to '%s' (text: %s)",
                        text, expectedInvokedTool, r.invokedTool(), r.text())
                .isEqualTo(expectedInvokedTool);
    }

    /** One real round-trip through the live model with the exact router prompt shape. */
    private String chat(String classifierPrompt, String userText) {
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(classifierPrompt),
                LlmMessage.user(userText)));
        LlmChatResponse resp = llm.chat(req).block(Duration.ofSeconds(90));
        assertThat(resp).as("no LLM response for «%s» — is llm-gateway up at %s?", userText, GoldenLlm.gatewayUrl()).isNotNull();
        return resp.content() == null ? "" : resp.content();
    }

    /** Lenient extraction: tolerate ```json fences / leading prose, parse the first JSON object found. */
    private JsonNode extractJson(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode n = json.readTree(raw.substring(start, end + 1));
            return n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ToolDefinition tool(String name, String description) {
        return DefaultToolDefinition.builder()
                .name(name).description(description)
                .inputSchema("{\"type\":\"object\"}").build();
    }
}
