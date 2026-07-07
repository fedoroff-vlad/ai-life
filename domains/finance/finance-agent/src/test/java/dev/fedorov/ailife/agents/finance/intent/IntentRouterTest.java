package dev.fedorov.ailife.agents.finance.intent;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.advisor.FinancialAdvisor;
import dev.fedorov.ailife.agents.finance.advisor.InvestmentAdvisor;
import dev.fedorov.ailife.agents.finance.category.CategoryManager;
import dev.fedorov.ailife.agents.finance.report.MonthlyReporter;
import dev.fedorov.ailife.agents.finance.report.YearReporter;
import dev.fedorov.ailife.agents.finance.tools.ToolDispatcher;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IntentRouter}. We feed mock {@link LlmClient} and
 * {@link ToolDispatcher} responses so the router's branches are exercised
 * without an external service. The five tests pin down:
 * <ul>
 *   <li>No tools wired → chat-only fallback (back-compat with pre-PR35).</li>
 *   <li>LLM picks a tool → dispatcher invoked with the exact args JSON.</li>
 *   <li>LLM picks chat → text reply propagates verbatim.</li>
 *   <li>LLM returns non-JSON → treated as raw chat reply (lenient parsing).</li>
 *   <li>Tool dispatch failure → user-facing Russian-locale error, not
 *   propagated as an exception.</li>
 *   <li>LLM picks {@code advice} → hands off to {@link FinancialAdvisor} and
 *   returns its synthesized analysis.</li>
 * </ul>
 */
class IntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final FinancialAdvisor advisor = mock(FinancialAdvisor.class);
    private final InvestmentAdvisor investmentAdvisor = mock(InvestmentAdvisor.class);
    private final MonthlyReporter monthlyReporter = mock(MonthlyReporter.class);
    private final YearReporter yearReporter = mock(YearReporter.class);
    private final CategoryManager categoryManager = mock(CategoryManager.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "finance", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the finance agent for the ai-life system.");

    private final IntentRouter router =
            new IntentRouter(llm, dispatcher, advisor, investmentAdvisor, monthlyReporter, yearReporter,
                    categoryManager, manifest, json);

    /** A minimal text message — what the orchestrator forwards on a user intent. */
    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    @Test
    void noToolsWiredFallsBackToPlainChatNoRoutingPrompt() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of());
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("mock-large", "Конечно, помогу."));
        });

        StepVerifier.create(router.route(msg("Привет")))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("Конечно, помогу.");
                    assertThat(r.invokedTool()).isNull();
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        // The fallback path uses ONLY two messages: AGENT.md body + user
        // text. No routing/classifier prompt — confirms tools=empty
        // short-circuits before that branch.
        assertThat(seen.get().messages()).hasSize(2);
        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmPicksToolAndDispatcherIsInvokedWithExactArgsJson() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("import_moneypro_csv",
                "Import Money Pro CSV history.")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"tool\",\"name\":\"import_moneypro_csv\","
                        + "\"args\":{\"dryRun\":true,\"householdId\":\"abc\"}}")));
        when(dispatcher.dispatch(eq("import_moneypro_csv"),
                eq("{\"dryRun\":true,\"householdId\":\"abc\"}")))
                .thenReturn("{\"created\":0,\"skipped\":0}");

        StepVerifier.create(router.route(msg("импортируй CSV из Money Pro, dry-run")))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("{\"created\":0,\"skipped\":0}");
                    assertThat(r.invokedTool()).isEqualTo("import_moneypro_csv");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();
    }

    @Test
    void llmPicksChatAndTextFieldPropagatesVerbatim() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("import_moneypro_csv", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"chat\",\"text\":\"Прикрепи CSV-файл и скажи как мапить счета.\"}")));

        StepVerifier.create(router.route(msg("импортируй csv")))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("Прикрепи CSV-файл и скажи как мапить счета.");
                    assertThat(r.invokedTool()).isNull();
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmReturnsNonJsonTreatsRawBodyAsChatReply() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("import_moneypro_csv", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "Извини, не понял запрос."))); // not JSON at all

        StepVerifier.create(router.route(msg("???")))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("Извини, не понял запрос.");
                    assertThat(r.invokedTool()).isNull();
                })
                .verifyComplete();

        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void toolDispatchFailureSurfacesAsUserFacingErrorWithoutThrowing() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("import_moneypro_csv", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"tool\",\"name\":\"import_moneypro_csv\",\"args\":{}}")));
        doThrow(new IllegalArgumentException("Missing required field: householdId"))
                .when(dispatcher).dispatch(eq("import_moneypro_csv"), eq("{}"));

        StepVerifier.create(router.route(msg("импортируй")))
                .assertNext(r -> {
                    // The user gets a friendly error mentioning the tool name
                    // and the dispatcher's own message — no exception is
                    // bubbled up to the controller.
                    assertThat(r.text()).contains("import_moneypro_csv");
                    assertThat(r.text()).contains("Missing required field");
                    assertThat(r.invokedTool()).isEqualTo("import_moneypro_csv");
                })
                .verifyComplete();
    }

    @Test
    void llmPicksAdviceHandsOffToFinancialAdvisor() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("spending_by_category", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"advice\"}")));
        when(advisor.advise(any(NormalizedMessage.class))).thenReturn(Mono.just(
                new FinancialAdvisor.AdviceResult("Больше всего ушло на еду: 300 EUR.", "mock-large")));

        StepVerifier.create(router.route(msg("проанализируй мои траты")))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("Больше всего ушло на еду: 300 EUR.");
                    assertThat(r.invokedTool()).isEqualTo("advice");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        // The analysis path does NOT touch the tool dispatcher.
        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmPicksInvestHandsOffToInvestmentAdvisorWithMappedSymbols() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("add_transaction", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"invest\",\"symbols\":[\"aapl.us\",\"xauusd\"]}")));
        when(investmentAdvisor.advise(any(NormalizedMessage.class), eq(List.of("aapl.us", "xauusd"))))
                .thenReturn(Mono.just(new InvestmentAdvisor.AdviceResult(
                        "Apple ~201 USD; золото ~2300. Это к размышлению, решать вам.", "mock-large")));

        StepVerifier.create(router.route(msg("что думаешь про Apple и золото?")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Apple").contains("решать вам");
                    assertThat(r.invokedTool()).isEqualTo("invest");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        // Advisory is its own gather+synthesis flow — no tool dispatch.
        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmPicksReportHandsOffToMonthlyReporter() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("spending_by_category", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"report\",\"period\":\"month\"}")));
        when(monthlyReporter.report(any(NormalizedMessage.class))).thenReturn(Mono.just(
                new MonthlyReporter.ReportResult("Собрал отчёт за июнь.\n\nПолный отчёт: http://m/v1/media/x",
                        "mock-large")));

        StepVerifier.create(router.route(msg("отчёт за месяц")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Полный отчёт");
                    assertThat(r.invokedTool()).isEqualTo("report");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        // The report path builds its own deliverable — no tool dispatch.
        verify(dispatcher, never()).dispatch(anyString(), anyString());
        // No period (or month) → the monthly reporter, not the year one.
        verify(yearReporter, never()).report(any(NormalizedMessage.class));
    }

    @Test
    void llmPicksReportPeriodYearHandsOffToYearReporter() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("spending_by_category", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"report\",\"period\":\"year\"}")));
        when(yearReporter.report(any(NormalizedMessage.class))).thenReturn(Mono.just(
                new MonthlyReporter.ReportResult("Собрал отчёт за 2026 год.\n\nПолный отчёт: http://m/v1/media/y",
                        "mock-large")));

        StepVerifier.create(router.route(msg("отчёт за год")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("2026").contains("Полный отчёт");
                    assertThat(r.invokedTool()).isEqualTo("report");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        // period=year → the year reporter, not the monthly one.
        verify(monthlyReporter, never()).report(any(NormalizedMessage.class));
        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmPicksCategoryHandsOffToCategoryManager() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("upsert_category", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"category\"}")));
        when(categoryManager.manage(any(NormalizedMessage.class))).thenReturn(Mono.just(
                new CategoryManager.CategoryResult("Готово. Категории: Кофейни (в группе «Еда»).", "mock-large")));

        StepVerifier.create(router.route(msg("заведи категорию Кофейни в группе Еда")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Кофейни");
                    assertThat(r.invokedTool()).isEqualTo("category");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        // The category flow does its own gather + apply — no direct tool dispatch.
        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }

    private static ToolDefinition toolDef(String name, String description) {
        return DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema("{\"type\":\"object\"}")
                .build();
    }
}
