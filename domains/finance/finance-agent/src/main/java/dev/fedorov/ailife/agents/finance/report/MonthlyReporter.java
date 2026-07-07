package dev.fedorov.ailife.agents.finance.report;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.ChartRenderClient;
import dev.fedorov.ailife.agents.finance.http.SpendingClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.chart.ChartSpec;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.docrender.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The on-request <b>monthly finance report</b> (#196) — the finance domain's first
 * Telegram <i>deliverable</i>, text-first. When the user asks "отчёт за месяц" /
 * "финансовый отчёт", the {@link dev.fedorov.ailife.agents.finance.intent.IntentRouter}
 * classifier routes here.
 *
 * <p>Distinct from {@code financial-advisor} (a quick chat analysis): this renders a
 * persistent <b>HTML report board</b> for the current calendar month and returns a
 * Telegram link, the same render → store → link seam the deliverable agents
 * (chef / nutritionist / stylist / creator) use ({@link DeliverablePublisher}).
 *
 * <p>Built on the shared {@link Coordinator}: it gathers the current month's
 * spend-by-category from mcp-finance's {@code /internal/spending-by-category}
 * passthrough, asks the LLM to synthesize a concise narrative from
 * {@code [AGENT.md, monthly-report SKILL.md] + {payload(month), context.byCategory}},
 * then builds a {@link Doc} pairing that narrative with a <b>deterministic</b>
 * category breakdown (the real numbers, not LLM-generated) and publishes it.
 *
 * <p>The board leads with a spending-by-category <b>bar chart</b> rendered by the
 * shared {@code mcp-chart-render} capability (#292, via {@link ChartRenderClient}) and
 * embedded by public media URL; the chart is soft-failed, so a render hiccup still ships
 * the text report.
 *
 * <p>No spending this month → a friendly invite (no LLM call, no board). A
 * render/store failure still hands back the textual narrative; any other failure
 * degrades to a friendly message.
 */
@Component
public class MonthlyReporter {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReporter.class);
    private static final String SKILL_NAME = "monthly-report";

    /** Cap the number of category bars so the chart stays readable. */
    private static final int MAX_CHART_CATEGORIES = 8;

    private final Coordinator coordinator;
    private final SpendingClient spending;
    private final DeliverablePublisher publisher;
    private final ChartRenderClient chartRender;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public MonthlyReporter(Coordinator coordinator,
                           SpendingClient spending,
                           DeliverablePublisher publisher,
                           ChartRenderClient chartRender,
                           SkillRegistry skills,
                           AgentManifest manifest,
                           ObjectMapper json) {
        this.coordinator = coordinator;
        this.spending = spending;
        this.publisher = publisher;
        this.chartRender = chartRender;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<ReportResult> report(NormalizedMessage msg) {
        UUID household = msg == null ? null : msg.householdId();
        if (household == null) {
            return Mono.just(new ReportResult(
                    "Не вижу, чей это бюджет — не могу собрать отчёт.", null));
        }
        Instant now = Instant.now();
        ZonedDateTime nowZ = now.atZone(ZoneOffset.UTC);
        Instant monthStart = nowZ.toLocalDate().withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        String monthLabel = ReportFormatting.MONTHS_RU[nowZ.getMonthValue() - 1] + " " + nowZ.getYear();

        return spending.spendingByCategory(household, monthStart, now)
                .flatMap(rows -> {
                    if (rows.isEmpty()) {
                        return Mono.just(new ReportResult(
                                "За " + monthLabel + " трат пока не записано — отчитываться не о чём. "
                                        + "Добавьте операции (чек фото или заметкой), и я соберу отчёт.", null));
                    }
                    return synthesize(msg, rows, monthLabel);
                })
                .onErrorResume(e -> {
                    log.warn("monthly-report failed for household {}: {}", household, e.toString());
                    return Mono.just(new ReportResult(
                            "Не смог собрать финансовый отчёт. Попробуйте позже.", null));
                });
    }

    private Mono<ReportResult> synthesize(NormalizedMessage msg, List<SpendingByCategoryRow> rows, String monthLabel) {
        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("byCategory", Mono.just(json.valueToTree(rows)));

        ObjectNode payload = json.createObjectNode();
        if (msg.text() != null && !msg.text().isBlank()) {
            payload.put("userText", msg.text());
        }
        payload.put("month", monthLabel);

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(result -> store(msg, result.text(), rows, monthLabel)
                        .map(link -> new ReportResult(
                                DeliverablePublisher.summary(result.text(), "Собрал финансовый отчёт за " + monthLabel + ".")
                                        + "\n\nПолный отчёт: " + link, result.llmModel()))
                        .onErrorResume(e -> {
                            log.warn("monthly-report render/store failed: {}", e.toString());
                            // Still hand back the narrative if the board couldn't be stored.
                            return Mono.just(new ReportResult(result.text(), result.llmModel()));
                        }));
    }

    /** Render the report board (chart + narrative + deterministic breakdown), store it, return the public link. */
    private Mono<String> store(NormalizedMessage msg, String narrative, List<SpendingByCategoryRow> rows, String monthLabel) {
        return chartUrl(msg, rows, monthLabel).flatMap(chartUrl -> {
            Doc.Builder doc = Doc.builder("Финансовый отчёт")
                    .kicker("Финансы · Отчёт за месяц")
                    .subtitle(monthLabel + " · " + ReportFormatting.totalsLine(rows));
            if (!chartUrl.isBlank()) {
                doc.chart(chartUrl);
            }
            doc.section("Сводка", DeliverablePublisher.splitParagraphs(narrative))
                    .section("Расходы по категориям", ReportFormatting.categoryLines(rows));
            return publisher.publish(msg.householdId(), msg.userId(), doc.build());
        });
    }

    /**
     * Render a spending-by-category bar chart via the shared {@code mcp-chart-render} capability and
     * return the public URL of the stored image. Soft-fail: any failure (or nothing to plot) yields
     * an empty string so the report still ships without the chart.
     */
    private Mono<String> chartUrl(NormalizedMessage msg, List<SpendingByCategoryRow> rows, String monthLabel) {
        ChartSpec spec = ReportFormatting.categoryChartSpec(
                rows, "Расходы по категориям · " + monthLabel, MAX_CHART_CATEGORIES);
        if (spec == null) {
            return Mono.just("");
        }
        return chartRender.render(msg.householdId(), msg.userId(), spec)
                .map(r -> {
                    String url = publisher.mediaUrl(r.mediaId());
                    return url == null ? "" : url;
                })
                .onErrorResume(e -> {
                    log.warn("monthly-report chart render failed: {}", e.toString());
                    return Mono.just("");
                });
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "monthly-report SKILL.md not loaded — check skills-classpath"));
    }

    /** The chat reply (summary + link, or a friendly fallback) plus the model that produced the narrative. */
    public record ReportResult(String text, String model) {
    }
}
