package dev.fedorov.ailife.agents.finance.intent;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.agents.finance.account.AccountManager;
import dev.fedorov.ailife.agents.finance.advisor.FinancialAdvisor;
import dev.fedorov.ailife.agents.finance.advisor.InvestmentAdvisor;
import dev.fedorov.ailife.agents.finance.category.CategoryManager;
import dev.fedorov.ailife.agents.finance.report.MonthlyReporter;
import dev.fedorov.ailife.agents.finance.report.YearReporter;
import dev.fedorov.ailife.agents.finance.tools.ToolDispatcher;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.Choice;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.ToolSpec;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Routes a user message into either an MCP tool call, one of finance's multi-step flows
 * (spending analysis / report / investment advisory / category management) or a plain LLM reply.
 *
 * <p>The prompt-build + strict-JSON-parse halves live in the shared
 * {@link SkillClassifier} ({@code libs/agent-runtime}, skills-vs-flows Bucket 1) — this agent keeps
 * only the parts that are genuinely its own: the LLM round-trip, the {@link ToolSpec}s it maps from
 * its wired MCP tools, and a small <b>pluggable flow-map</b> ({@link #flows}) that dispatches each
 * classifier {@link SkillClassifier.FlowCall} to the Coordinator-backed flow it names. Each flow's
 * trigger phrasing is sourced from its SKILL.md {@code description} (the SSOT — see {@link #flowTrigger}).
 *
 * <p>Pipeline (single LLM round-trip):
 * <ol>
 *   <li>If {@link ToolDispatcher#availableToolDefinitions()} is empty (MCP client disabled or no
 *   servers wired), skip routing entirely and fall back to the plain chat path — back-compat with the
 *   pre-PR35 behaviour.</li>
 *   <li>Otherwise prompt the LLM with AGENT.md body + the classifier prompt (tool list + flow blocks +
 *   the strict-JSON contract), then hand the raw response to {@link SkillClassifier#parse}.</li>
 *   <li>{@link SkillClassifier.ToolCall} → dispatch via {@link ToolDispatcher}; {@link
 *   SkillClassifier.FlowCall} → the matching {@link #flows} handler; {@link SkillClassifier.Chat} →
 *   the reply text (the lenient fallback keeps a chatty model from breaking the agent).</li>
 * </ol>
 *
 * <p>Tool-dispatch failures (unknown name, invalid args, MCP server down) are caught and surfaced to
 * the user as a short Russian-locale error string with the tool name — the LLM might re-route on a
 * follow-up, or the user can adjust their request. We deliberately do NOT retry or re-route here;
 * multi-turn recovery is the orchestrator's concern.
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    /**
     * Finance-specific enum-pinning rule (Stage 5 / #199 golden finding): a 7B otherwise invents
     * action values like {@code "analysis"} or drops a tool name into the {@code action} field. Passed
     * to {@link SkillClassifier#buildPrompt} as an {@code extraRule} so it lands right after the shape
     * list and before the shared missing-argument rule — the exact slot it occupied pre-migration.
     */
    private static final String ENUM_PINNING =
            "The \"action\" value MUST be exactly one of these literal strings: "
                    + "\"tool\", \"advice\", \"report\", \"invest\", \"category\", \"account\", \"chat\". Do NOT invent "
                    + "any other action value (not \"analysis\", not a tool name in the action field — a tool goes in "
                    + "\"name\" with action \"tool\"). For a spending analysis use exactly \"advice\".\n";

    private final LlmClient llm;
    private final ToolDispatcher dispatcher;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final SkillClassifier classifier;

    /**
     * The agent-supplied flow dispatch: classifier {@code action} → the Coordinator-backed flow that
     * handles it, given the user message + the parsed routing node (from which a flow reads its own
     * fields, e.g. {@code period}/{@code symbols}). Keys mirror {@link #choices()} exactly, so a
     * {@link SkillClassifier.FlowCall} always resolves to a handler here.
     */
    private final Map<String, BiFunction<NormalizedMessage, JsonNode, Mono<RouterResult>>> flows;

    public IntentRouter(LlmClient llm,
                        ToolDispatcher dispatcher,
                        FinancialAdvisor advisor,
                        InvestmentAdvisor investmentAdvisor,
                        MonthlyReporter monthlyReporter,
                        YearReporter yearReporter,
                        CategoryManager categoryManager,
                        AccountManager accountManager,
                        AgentManifest manifest,
                        SkillRegistry skills,
                        SkillClassifier classifier) {
        this.llm = llm;
        this.dispatcher = dispatcher;
        this.manifest = manifest;
        this.skills = skills;
        this.classifier = classifier;
        this.flows = Map.of(
                "advice", (msg, node) -> advisor
                        .advise(msg, "shared".equalsIgnoreCase(node.path("scope").asString("")))
                        .map(a -> new RouterResult(a.text(), "advice", a.model())),
                "invest", (msg, node) -> investmentAdvisor.advise(msg, readSymbols(node))
                        .map(a -> new RouterResult(a.text(), "invest", a.model())),
                // period=year → the YearReporter (year window + per-month trend chart); anything else
                // (default month) → the MonthlyReporter. Both are Coordinator-backed HTML deliverables, and
                // both honour scope=shared → the family cut (personal ∪ shared households), else own only.
                "report", (msg, node) -> {
                    boolean shared = "shared".equalsIgnoreCase(node.path("scope").asString(""));
                    return "year".equalsIgnoreCase(node.path("period").asString(""))
                            ? yearReporter.report(msg, shared).map(r -> new RouterResult(r.text(), "report", r.model()))
                            : monthlyReporter.report(msg, shared).map(r -> new RouterResult(r.text(), "report", r.model()));
                },
                "category", (msg, node) -> categoryManager.manage(msg)
                        .map(r -> new RouterResult(r.text(), "category", r.model(), null,
                                "wrote: updated your spending categories")),
                // The account flow may defer + ask "личное или общее?" (item 8, DS-N): thread its
                // pendingAction through so the orchestrator locks the conversation to finance /resume.
                // A non-deferred (pendingAction == null) create actually wrote → a why-trace; a defer none.
                "account", (msg, node) -> accountManager.create(msg)
                        .map(r -> new RouterResult(r.text(), "account", r.model(), r.pendingAction(),
                                r.pendingAction() == null ? "wrote: created a finance account" : null)));
    }

    public Mono<RouterResult> route(NormalizedMessage msg) {
        String userText = msg == null || msg.text() == null ? "" : msg.text();
        List<ToolDefinition> tools = dispatcher.availableToolDefinitions();
        if (tools.isEmpty()) {
            // No MCP tools wired — straight chat, no routing prompt overhead, no JSON-parse surprises.
            // (Production finance-agent always has MCP wired, so the flow branches below are reachable.)
            return chatOnly(userText);
        }

        List<ToolSpec> toolSpecs = toolSpecs(tools);
        List<Choice> choices = choices();
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(buildClassifierPrompt(tools)),
                LlmMessage.user(userText)));

        return llm.chat(req).flatMap(resp -> {
            String raw = resp.content() == null ? "" : resp.content().trim();
            String model = resp.model();
            return switch (classifier.parse(raw, toolSpecs, choices)) {
                case SkillClassifier.ToolCall t -> invokeTool(t.name(), t.argsJson(), model);
                // The flow-map keys mirror choices() one-for-one, so this lookup never misses.
                case SkillClassifier.FlowCall f -> flows.get(f.action()).apply(msg, f.node());
                case SkillClassifier.Chat c -> Mono.just(new RouterResult(c.text(), null, model));
            };
        });
    }

    private Mono<RouterResult> chatOnly(String userText) {
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(userText)));
        return llm.chat(req).map(r -> new RouterResult(
                r.content() == null ? "" : r.content(), null, r.model()));
    }

    private Mono<RouterResult> invokeTool(String name, String argsJson, String llmModel) {
        // ToolCallback.call is blocking (SSE under the hood) — same handling as InternalToolsController.
        return Mono.fromCallable(() -> dispatcher.dispatch(name, argsJson))
                .subscribeOn(Schedulers.boundedElastic())
                // A successful WRITE tool contributes a payload-free why-trace (#485/G2); reads/failures none.
                .map(result -> new RouterResult(result, name, llmModel, null, writeToolTrace(name)))
                .onErrorResume(e -> {
                    log.warn("tool dispatch failed for {}: {}", name, e.toString());
                    return Mono.just(new RouterResult(
                            "Не удалось вызвать инструмент «" + name + "»: " + e.getMessage(),
                            name, llmModel));
                });
    }

    /**
     * The payload-free why-trace for a successful WRITE tool, or null for a read/unknown tool (#485/G2).
     * Only the writing MCP tools finance routes to directly get one; the rest carry no trace, so the
     * explain answer falls back to the routing-only line.
     */
    private static String writeToolTrace(String toolName) {
        // Only add_transaction is an unconditional write. import_moneypro_csv has a dry-run mode (writes
        // nothing), so a coarse tool-name trace would lie there — left out on purpose.
        return "add_transaction".equals(toolName) ? "wrote: recorded a transaction" : null;
    }

    /** Map the wired MCP tools to the classifier's domain-agnostic {@link ToolSpec}s at the call site. */
    private List<ToolSpec> toolSpecs(List<ToolDefinition> tools) {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (ToolDefinition t : tools) {
            specs.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
        }
        return specs;
    }

    /**
     * The finance flow choices offered to the classifier. Each {@link Choice}'s {@code promptBlock} is
     * sourced from its SKILL.md {@code description} (via {@link #flowTrigger}, the single source of
     * truth); only the JSON shape and the per-flow mechanics/disambiguation live here, next to the
     * {@link #flows} dispatch they drive. Keys mirror {@link #flows} one-for-one.
     */
    private List<Choice> choices() {
        return List.of(
                new Choice("advice",
                        "There is also a built-in spending ANALYSIS flow (not a tool): "
                                + flowTrigger("financial-advisor",
                                "use it when the user asks to analyse / review their own spending or wants recommendations.")
                                + " Prefer it over the monthly REPORT when the user wants advice / reasons / where to save."
                                + " By default it analyses the user's OWN spending; add \"scope\":\"shared\" ONLY when they"
                                + " explicitly ask about SHARED / FAMILY / joint spending (\"наши траты\", \"семейный бюджет\").",
                        "{\"action\":\"advice\"}"),
                new Choice("report",
                        "There is also a built-in REPORT flow (not a tool): "
                                + flowTrigger("monthly-report",
                                "use it when the user asks for a finance report or a period summary as a document.")
                                + " " + flowTrigger("year-report", "The annual variant of the finance report.")
                                + " It builds an HTML report of the spending and returns a link. Set \"period\":\"year\" "
                                + "when the user asks for the YEAR / annual summary; otherwise \"period\":\"month\" "
                                + "(the default — current month)."
                                + " By default it reports the user's OWN spending; add \"scope\":\"shared\" ONLY when they"
                                + " explicitly ask for a SHARED / FAMILY report (\"семейный отчёт\", \"наши траты\").",
                        "{\"action\":\"report\",\"period\":\"month\"}"),
                new Choice("invest",
                        "There is also a built-in INVESTMENT ADVISORY flow (not a tool): "
                                + flowTrigger("investment-advisor",
                                "use it when the user asks for an opinion on stocks, funds/ETFs, metals, forex or crypto.")
                                + " It is ADVISORY ONLY — it never trades. Map each asset the user named to its "
                                + "source-native symbol and pass them in 'symbols': US stocks use a '.us' suffix "
                                + "(Apple→aapl.us), indices a '^' prefix (S&P 500→^spx), gold→xauusd, silver→xagusd, "
                                + "bitcoin→btcusd, ether→ethusd. Include only assets the user actually named.",
                        "{\"action\":\"invest\",\"symbols\":[\"aapl.us\",\"xauusd\"]}"),
                new Choice("category",
                        "There is also a built-in CATEGORY-MANAGEMENT flow (not a tool): "
                                + flowTrigger("category-manager",
                                "use it when the user wants to CREATE or GROUP their spending categories.")
                                + " It reads the existing categories and applies the changes itself. This is about the "
                                + "category LIST/structure — not recording a spend (that's the add_transaction tool) "
                                + "and not analysing spend (that's advice).",
                        "{\"action\":\"category\"}"),
                new Choice("account",
                        "There is also a built-in ACCOUNT-CREATION flow (not a tool): "
                                + flowTrigger("account-manager",
                                "use it when the user wants to CREATE / open a new finance account (card, cash, "
                                        + "deposit, credit).")
                                + " It plans the account and creates it itself, routing it to the personal or the "
                                + "shared household. This is about opening an ACCOUNT — not recording a spend (that's "
                                + "the add_transaction tool) and not managing categories (that's category).",
                        "{\"action\":\"account\"}"));
    }

    /**
     * Build the classifier system prompt via the shared {@link SkillClassifier#buildPrompt} scaffold,
     * then append the finance-specific enum-pinning tail. Kept package-private (not private) so the
     * {@code @Tag("golden")} real-model test can replay the exact classifier prompt against a live
     * model and assert the raw output's structure — single-sourced.
     */
    String buildClassifierPrompt(List<ToolDefinition> tools) {
        return classifier.buildPrompt(
                "You can either reply directly to the user or invoke one of these MCP tools.",
                toolSpecs(tools),
                choices(),
                "Decide: run a tool, run the spending analysis, build the finance report, "
                        + "run the investment advisory, manage categories, create an account, or just talk?",
                List.of(ENUM_PINNING));
    }

    /**
     * A flow's trigger line, sourced from its SKILL.md {@code description} (the single source of
     * truth). Falls back to a terse built-in line if the skill isn't loaded, so a missing skill
     * degrades the prompt rather than breaking routing.
     */
    private String flowTrigger(String skillName, String fallback) {
        return skills.byName(skillName).map(Skill::description).orElse(fallback);
    }

    /** Pull the {@code symbols} string array out of an {@code action=invest} routing node. */
    private List<String> readSymbols(JsonNode node) {
        JsonNode arr = node.get("symbols");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> symbols = new ArrayList<>();
        for (JsonNode s : arr) {
            if (s != null && s.isString() && !s.asString().isBlank()) {
                symbols.add(s.asString());
            }
        }
        return symbols;
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
     *   <li>{@code pendingAction} — non-null only when a flow deferred to ask a
     *   confirmation (item 8, DS-N — the {@code account} flow's "личное или
     *   общее?"); {@link IntentController} threads it into the {@code
     *   IntentResponse} so the orchestrator route-locks the conversation. Null
     *   for every other outcome.</li>
     *   <li>{@code trace} — optional payload-free "what I wrote" line for the
     *   "why did you do that" answer (#485 / Track G2). Set only on a terminal
     *   WRITE outcome (a recorded transaction, a created account, a category
     *   change); null for reads (advice/report/invest), chat, deferrals, and
     *   failures. {@link IntentController} threads it via
     *   {@code IntentResponse.withTrace}.</li>
     * </ul>
     */
    public record RouterResult(String text, String invokedTool, String llmModel, JsonNode pendingAction,
                               String trace) {
        /** Back-compat for the common "no pending action" outcome (tool / chat / non-deferring reads). */
        public RouterResult(String text, String invokedTool, String llmModel) {
            this(text, invokedTool, llmModel, null, null);
        }

        /** Back-compat for a deferring flow (pending action, no write trace yet). */
        public RouterResult(String text, String invokedTool, String llmModel, JsonNode pendingAction) {
            this(text, invokedTool, llmModel, pendingAction, null);
        }
    }
}
