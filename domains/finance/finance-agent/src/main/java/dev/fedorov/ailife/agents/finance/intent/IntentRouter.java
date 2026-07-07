package dev.fedorov.ailife.agents.finance.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.advisor.FinancialAdvisor;
import dev.fedorov.ailife.agents.finance.advisor.InvestmentAdvisor;
import dev.fedorov.ailife.agents.finance.category.CategoryManager;
import dev.fedorov.ailife.agents.finance.report.MonthlyReporter;
import dev.fedorov.ailife.agents.finance.report.YearReporter;
import dev.fedorov.ailife.agents.finance.tools.ToolDispatcher;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Routes a user message into either an MCP tool call or a plain LLM reply.
 *
 * <p>Pipeline (single LLM round-trip):
 * <ol>
 *   <li>If {@link ToolDispatcher#availableToolDefinitions()} is empty (MCP
 *   client disabled or no servers wired), skip routing entirely and fall back
 *   to the plain chat path — back-compat with the pre-PR35 behaviour.</li>
 *   <li>Otherwise prompt the LLM with: AGENT.md body + a system message
 *   listing the available tools (name + description + input schema) and the
 *   required JSON output shape ({@code action: tool|chat}, plus
 *   {@code name + args} or {@code text}).</li>
 *   <li>Parse the LLM response. If it's well-formed JSON with
 *   {@code action: tool}, dispatch via {@link ToolDispatcher} and return the
 *   tool result; if {@code action: chat}, return its {@code text}; if the
 *   response can't be parsed (no opening brace, missing fields, …) we treat
 *   the raw response as the plain chat answer — lenient parsing keeps a
 *   chatty model from breaking the agent.</li>
 * </ol>
 *
 * <p>Tool-dispatch failures (unknown name, invalid args, MCP server down) are
 * caught and surfaced to the user as a short Russian-locale error string with
 * the tool name — the LLM might re-route on a follow-up, or the user can
 * adjust their request. We deliberately do NOT retry or re-route here;
 * multi-turn recovery is the orchestrator's concern.
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private final LlmClient llm;
    private final ToolDispatcher dispatcher;
    private final FinancialAdvisor advisor;
    private final InvestmentAdvisor investmentAdvisor;
    private final MonthlyReporter monthlyReporter;
    private final YearReporter yearReporter;
    private final CategoryManager categoryManager;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public IntentRouter(LlmClient llm,
                        ToolDispatcher dispatcher,
                        FinancialAdvisor advisor,
                        InvestmentAdvisor investmentAdvisor,
                        MonthlyReporter monthlyReporter,
                        YearReporter yearReporter,
                        CategoryManager categoryManager,
                        AgentManifest manifest,
                        ObjectMapper json) {
        this.llm = llm;
        this.dispatcher = dispatcher;
        this.advisor = advisor;
        this.investmentAdvisor = investmentAdvisor;
        this.monthlyReporter = monthlyReporter;
        this.yearReporter = yearReporter;
        this.categoryManager = categoryManager;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<RouterResult> route(NormalizedMessage msg) {
        String userText = msg == null || msg.text() == null ? "" : msg.text();
        List<ToolDefinition> tools = dispatcher.availableToolDefinitions();
        if (tools.isEmpty()) {
            // No MCP tools wired — straight chat, no routing prompt
            // overhead, no JSON-parse surprises. (Production finance-agent
            // always has MCP wired, so the advice branch below is reachable.)
            return chatOnly(userText);
        }

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(buildClassifierPrompt(tools)),
                LlmMessage.user(userText)));

        return llm.chat(req).flatMap(resp -> {
            String raw = resp.content() == null ? "" : resp.content().trim();
            String model = resp.model();
            JsonNode node = tryParse(raw);
            if (node == null || !node.isObject() || !node.hasNonNull("action")) {
                // Not a routing JSON — treat as a plain chat reply.
                return Mono.just(new RouterResult(raw, null, model));
            }
            String action = node.get("action").asText();
            if ("tool".equals(action)) {
                return invokeTool(node.path("name").asText(), node.path("args"), model);
            }
            // Format-drift tolerance (Stage 5 golden finding): smaller models (e.g. qwen2.5:7b) often
            // flatten the two-level shape to {"action":"<toolName>", "args":{…}} instead of
            // {"action":"tool","name":"<toolName>"}. Accept it when the action *is* a known tool name.
            if (isKnownTool(action, tools)) {
                return invokeTool(action, node.path("args"), model);
            }
            if ("advice".equals(action)) {
                // Spending analysis is multi-source synthesis, not a single tool
                // — hand off to the Coordinator-backed FinancialAdvisor, which
                // does its own gather + LLM synthesis and returns the analysis.
                return advisor.advise(msg)
                        .map(a -> new RouterResult(a.text(), "advice", a.model()));
            }
            if ("invest".equals(action)) {
                // Investment advisory (advisory-only): the classifier already mapped the
                // user's tickers to source-native symbols — gather a quote per symbol and
                // let the Coordinator-backed InvestmentAdvisor synthesize considerations.
                return investmentAdvisor.advise(msg, readSymbols(node))
                        .map(a -> new RouterResult(a.text(), "invest", a.model()));
            }
            if ("report".equals(action)) {
                // A persistent finance report (HTML board → Telegram link), not a chat analysis.
                // period=year → the YearReporter (year window + per-month trend chart); anything
                // else (default month) → the MonthlyReporter. Both are Coordinator-backed: gather
                // the spending, synthesize a narrative, render + store the board, return the link.
                if ("year".equalsIgnoreCase(node.path("period").asText(""))) {
                    return yearReporter.report(msg)
                            .map(r -> new RouterResult(r.text(), "report", r.model()));
                }
                return monthlyReporter.report(msg)
                        .map(r -> new RouterResult(r.text(), "report", r.model()));
            }
            if ("category".equals(action)) {
                // Create / group finance categories from chat — a multi-step flow (list existing →
                // LLM plan → resolve parent by name → upsert), not a single tool. Hand off to
                // CategoryManager, which does its own gather + apply and returns a confirmation.
                return categoryManager.manage(msg)
                        .map(r -> new RouterResult(r.text(), "category", r.model()));
            }
            // action=chat (or anything else we don't recognise): prefer the
            // structured 'text' field, else fall back to the raw body.
            String text = node.has("text") ? node.get("text").asText() : raw;
            return Mono.just(new RouterResult(text, null, model));
        });
    }

    private Mono<RouterResult> chatOnly(String userText) {
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(userText)));
        return llm.chat(req).map(r -> new RouterResult(
                r.content() == null ? "" : r.content(), null, r.model()));
    }

    /** True when {@code action} names one of the wired MCP tools (the flattened-shape case). */
    private boolean isKnownTool(String action, List<ToolDefinition> tools) {
        for (ToolDefinition t : tools) {
            if (t.name().equals(action)) {
                return true;
            }
        }
        return false;
    }

    private Mono<RouterResult> invokeTool(String name, JsonNode argsNode, String llmModel) {
        String argsJson = argsNode == null || argsNode.isMissingNode() || argsNode.isNull()
                ? "{}" : argsNode.toString();
        // ToolCallback.call is blocking (SSE under the hood) — same handling
        // as InternalToolsController.
        return Mono.fromCallable(() -> dispatcher.dispatch(name, argsJson))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> new RouterResult(result, name, llmModel))
                .onErrorResume(e -> {
                    log.warn("tool dispatch failed for {}: {}", name, e.toString());
                    return Mono.just(new RouterResult(
                            "Не удалось вызвать инструмент «" + name + "»: " + e.getMessage(),
                            name, llmModel));
                });
    }

    /** Pull the {@code symbols} string array out of an {@code action=invest} routing node. */
    private List<String> readSymbols(JsonNode node) {
        JsonNode arr = node.get("symbols");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> symbols = new java.util.ArrayList<>();
        for (JsonNode s : arr) {
            if (s != null && s.isTextual() && !s.asText().isBlank()) {
                symbols.add(s.asText());
            }
        }
        return symbols;
    }

    private JsonNode tryParse(String raw) {
        if (raw.isEmpty() || raw.charAt(0) != '{') return null;
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build the system prompt that tells the LLM about the available tools
     * and the strict JSON output contract. Kept inline (not externalised to a
     * template file) because it's tightly coupled to the parsing on the Java
     * side — the two evolve together.
     */
    // Package-private (not private) so the @Tag("golden") real-model test can replay the exact
    // classifier prompt against a live model and assert the raw output's structure — single-sourced.
    String buildClassifierPrompt(List<ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You can either reply directly to the user or invoke one of these MCP tools.\n\n");
        sb.append("Available tools:\n");
        for (ToolDefinition t : tools) {
            sb.append("- ").append(t.name()).append(": ").append(t.description());
            String schema = t.inputSchema();
            if (schema != null && !schema.isBlank()) {
                sb.append("\n  inputSchema: ").append(schema);
            }
            sb.append('\n');
        }
        sb.append('\n');
        sb.append("There is also a built-in spending ANALYSIS flow (not a tool): use it when the ");
        sb.append("user asks to analyse / review their own spending or wants ");
        sb.append("recommendations over their finances (e.g. \"проанализируй траты\", ");
        sb.append("\"куда уходят деньги\", \"где можно сэкономить\"). It gathers the data itself. ");
        sb.append("Prefer it over the monthly REPORT when the user wants advice / reasons / where to save.\n\n");
        sb.append("There is also a built-in REPORT flow (not a tool): use it when the user asks ");
        sb.append("for a finance report or a period summary as a document (e.g. \"отчёт за месяц\", ");
        sb.append("\"финансовый отчёт\", \"сводка за месяц\", \"отчитайся по тратам\", \"отчёт за год\", ");
        sb.append("\"итоги года\"). It builds an HTML report of the spending and returns a link; it ");
        sb.append("gathers the data itself. Set \"period\":\"year\" when the user asks for the YEAR / ");
        sb.append("annual summary (\"за год\", \"годовой\", \"итоги года\"); otherwise \"period\":\"month\" ");
        sb.append("(the default — current month).\n\n");
        sb.append("There is also a built-in INVESTMENT ADVISORY flow (not a tool): use it when the user ");
        sb.append("asks for an opinion / outlook on stocks, funds/ETFs, metals, forex or crypto (e.g. ");
        sb.append("\"что думаешь про Apple и золото?\", \"стоит ли смотреть на биткоин?\"). It is ");
        sb.append("ADVISORY ONLY — it never trades. Map each asset the user named to its source-native ");
        sb.append("symbol and pass them in 'symbols': US stocks use a '.us' suffix (Apple→aapl.us), ");
        sb.append("indices a '^' prefix (S&P 500→^spx), gold→xauusd, silver→xagusd, bitcoin→btcusd, ");
        sb.append("ether→ethusd. Include only assets the user actually named.\n\n");
        sb.append("There is also a built-in CATEGORY-MANAGEMENT flow (not a tool): use it when the user ");
        sb.append("wants to CREATE or GROUP their spending categories (e.g. \"заведи категорию Кофейни\", ");
        sb.append("\"создай категорию Подарки\", \"сгруппируй Такси и Метро под Транспорт\", \"добавь ");
        sb.append("категорию Кафе в группу Еда\"). It reads the existing categories and applies the ");
        sb.append("changes itself. This is about the category LIST/structure — not recording a spend ");
        sb.append("(that's the add_transaction tool) and not analysing spend (that's advice).\n\n");
        sb.append("Decide: run a tool, run the spending analysis, build the finance report, ");
        sb.append("run the investment advisory, manage categories, or just talk?\n\n");
        sb.append("Reply with strict JSON ONLY. No markdown fences, no commentary, no extra prose.\n");
        sb.append("Use ONE of these shapes:\n");
        sb.append("  {\"action\":\"tool\",\"name\":\"<tool-name>\",\"args\":{...}}\n");
        sb.append("  {\"action\":\"advice\"}\n");
        sb.append("  {\"action\":\"report\",\"period\":\"month\"}\n");
        sb.append("  {\"action\":\"invest\",\"symbols\":[\"aapl.us\",\"xauusd\"]}\n");
        sb.append("  {\"action\":\"category\"}\n");
        sb.append("  {\"action\":\"chat\",\"text\":\"<reply to the user>\"}\n\n");
        sb.append("The \"action\" value MUST be exactly one of these literal strings: ");
        sb.append("\"tool\", \"advice\", \"report\", \"invest\", \"category\", \"chat\". Do NOT invent any other ");
        sb.append("action value (not \"analysis\", not a tool name in the action field — a tool goes in ");
        sb.append("\"name\" with action \"tool\"). For a spending analysis use exactly \"advice\".\n");
        sb.append("If the user's message lacks required arguments for a tool, use action=chat ");
        sb.append("and ask the user (in their language) for the missing piece — do NOT invent ");
        sb.append("arguments. If they're just chatting, use action=chat with a helpful reply.\n");
        return sb.toString();
    }

    /**
     * Outcome of a single {@link #route} call.
     *
     * <ul>
     *   <li>{@code text} — what the orchestrator should show the user.</li>
     *   <li>{@code invokedTool} — non-null when the result came from a tool
     *   dispatch (currently informational — future PR may use it for audit /
     *   analytics, or to drive a "do you want me to apply this?" follow-up
     *   turn).</li>
     *   <li>{@code llmModel} — model id from the LLM round-trip (the routing
     *   turn). Preserves the pre-PR35 {@code IntentResponse.llmModel} contract
     *   the orchestrator's intent tests already assert on.</li>
     * </ul>
     */
    public record RouterResult(String text, String invokedTool, String llmModel) {
    }
}
