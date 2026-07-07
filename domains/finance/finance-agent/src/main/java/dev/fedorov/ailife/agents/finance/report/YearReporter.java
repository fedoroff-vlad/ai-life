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
import dev.fedorov.ailife.contracts.chart.ChartSeries;
import dev.fedorov.ailife.contracts.chart.ChartSpec;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.docrender.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The on-request <b>year-analysis finance report</b> (#291) — the year sibling of
 * {@link MonthlyReporter}. When the user asks "отчёт за год" / "финансовый отчёт за 2026", the
 * {@link dev.fedorov.ailife.agents.finance.intent.IntentRouter} classifier routes here (the
 * {@code report} action with {@code period:year}).
 *
 * <p>Built on the shared {@link Coordinator}: it gathers the current year's spend-by-category (one
 * {@code /internal/spending-by-category} window, Jan 1 → now) <b>and</b> a per-month spend trend
 * (one window per elapsed month, in parallel), asks the LLM to synthesize a narrative from
 * {@code [AGENT.md, year-report SKILL.md] + {payload(year), context.byCategory, context.monthlyTrend}},
 * then builds a {@link Doc} leading with two charts — a spending-by-category <b>bar</b> chart and a
 * per-month <b>line</b> trend, both rendered by the shared {@code mcp-chart-render} capability and
 * embedded by public media URL — followed by the narrative and a <b>deterministic</b> category
 * breakdown (the real numbers, not LLM-generated).
 *
 * <p>Each chart is soft-failed independently, so a render hiccup still ships the rest. No spending
 * this year → a friendly invite (no LLM/chart/store call); a render/store failure still hands back
 * the narrative; any other failure degrades to a friendly message.
 */
@Component
public class YearReporter {

    private static final Logger log = LoggerFactory.getLogger(YearReporter.class);
    private static final String SKILL_NAME = "year-report";

    /** Cap the number of category bars so the chart stays readable. */
    private static final int MAX_CHART_CATEGORIES = 10;

    private final Coordinator coordinator;
    private final SpendingClient spending;
    private final DeliverablePublisher publisher;
    private final ChartRenderClient chartRender;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public YearReporter(Coordinator coordinator,
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

    public Mono<MonthlyReporter.ReportResult> report(NormalizedMessage msg) {
        UUID household = msg == null ? null : msg.householdId();
        if (household == null) {
            return Mono.just(new MonthlyReporter.ReportResult(
                    "Не вижу, чей это бюджет — не могу собрать отчёт.", null));
        }
        Instant now = Instant.now();
        ZonedDateTime nowZ = now.atZone(ZoneOffset.UTC);
        int year = nowZ.getYear();
        int monthsElapsed = nowZ.getMonthValue();
        Instant yearStart = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String yearLabel = String.valueOf(year);

        return spending.spendingByCategory(household, yearStart, now)
                .flatMap(rows -> {
                    if (rows.isEmpty()) {
                        return Mono.just(new MonthlyReporter.ReportResult(
                                "За " + yearLabel + " год трат пока не записано — отчитываться не о чём. "
                                        + "Добавьте операции (чек фото или заметкой), и я соберу отчёт.", null));
                    }
                    return monthlyTrend(household, year, monthsElapsed, now)
                            .flatMap(trend -> synthesize(msg, rows, trend, yearLabel));
                })
                .onErrorResume(e -> {
                    log.warn("year-report failed for household {}: {}", household, e.toString());
                    return Mono.just(new MonthlyReporter.ReportResult(
                            "Не смог собрать годовой финансовый отчёт. Попробуйте позже.", null));
                });
    }

    private Mono<MonthlyReporter.ReportResult> synthesize(NormalizedMessage msg,
                                                          List<SpendingByCategoryRow> rows,
                                                          List<MonthTotal> trend,
                                                          String yearLabel) {
        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("byCategory", Mono.just(json.valueToTree(rows)));
        gather.put("monthlyTrend", Mono.just(json.valueToTree(trend)));

        ObjectNode payload = json.createObjectNode();
        if (msg.text() != null && !msg.text().isBlank()) {
            payload.put("userText", msg.text());
        }
        payload.put("year", yearLabel);

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(result -> store(msg, result.text(), rows, trend, yearLabel)
                        .map(link -> new MonthlyReporter.ReportResult(
                                DeliverablePublisher.summary(result.text(), "Собрал финансовый отчёт за " + yearLabel + " год.")
                                        + "\n\nПолный отчёт: " + link, result.llmModel()))
                        .onErrorResume(e -> {
                            log.warn("year-report render/store failed: {}", e.toString());
                            return Mono.just(new MonthlyReporter.ReportResult(result.text(), result.llmModel()));
                        }));
    }

    /** Render the board (two charts + narrative + deterministic breakdown), store it, return the link. */
    private Mono<String> store(NormalizedMessage msg, String narrative,
                               List<SpendingByCategoryRow> rows, List<MonthTotal> trend, String yearLabel) {
        ChartSpec barSpec = ReportFormatting.categoryChartSpec(
                rows, "Расходы по категориям · " + yearLabel, MAX_CHART_CATEGORIES);
        ChartSpec lineSpec = trendChartSpec(trend, rows, yearLabel);

        return Mono.zip(chartUrl(msg, barSpec), chartUrl(msg, lineSpec)).flatMap(urls -> {
            Doc.Builder doc = Doc.builder("Финансовый отчёт за год")
                    .kicker("Финансы · Годовой отчёт")
                    .subtitle(yearLabel + " · " + ReportFormatting.totalsLine(rows));
            if (!urls.getT2().isBlank()) doc.chart(urls.getT2()); // trend first — the year's shape
            if (!urls.getT1().isBlank()) doc.chart(urls.getT1()); // then the category breakdown
            doc.section("Сводка", DeliverablePublisher.splitParagraphs(narrative))
                    .section("Расходы по категориям", ReportFormatting.categoryLines(rows));
            return publisher.publish(msg.householdId(), msg.userId(), doc.build());
        });
    }

    /** Render one chart via the capability → its public URL; soft-fail (or no spec) → empty string. */
    private Mono<String> chartUrl(NormalizedMessage msg, ChartSpec spec) {
        if (spec == null) {
            return Mono.just("");
        }
        return chartRender.render(msg.householdId(), msg.userId(), spec)
                .map(r -> {
                    String url = publisher.mediaUrl(r.mediaId());
                    return url == null ? "" : url;
                })
                .onErrorResume(e -> {
                    log.warn("year-report chart render failed: {}", e.toString());
                    return Mono.just("");
                });
    }

    /** Fetch each elapsed month's total spend (parallel, order-preserving, per-month soft-fail). */
    private Mono<List<MonthTotal>> monthlyTrend(UUID household, int year, int monthsElapsed, Instant now) {
        List<Mono<MonthTotal>> perMonth = new ArrayList<>();
        for (int m = 1; m <= monthsElapsed; m++) {
            String label = ReportFormatting.MONTHS_RU_SHORT[m - 1];
            Instant from = LocalDate.of(year, m, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = (m < monthsElapsed)
                    ? LocalDate.of(year, m, 1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : now; // the current (last) month runs up to now
            perMonth.add(spending.spendingByCategory(household, from, to)
                    .map(monthRows -> new MonthTotal(label, sumAbs(monthRows)))
                    .onErrorReturn(new MonthTotal(label, 0.0)));
        }
        // mergeSequential runs the calls concurrently but emits results in month order.
        return Flux.mergeSequential(perMonth).collectList();
    }

    /** Build the per-month spend line-chart spec; null when there's nothing to plot. */
    private ChartSpec trendChartSpec(List<MonthTotal> trend, List<SpendingByCategoryRow> yearRows, String yearLabel) {
        boolean anyPositive = trend.stream().anyMatch(t -> t.total() > 0);
        if (trend.isEmpty() || !anyPositive) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (MonthTotal t : trend) {
            labels.add(t.month());
            values.add(t.total());
        }
        return new ChartSpec("line", "Расходы по месяцам · " + yearLabel,
                labels, List.of(new ChartSeries("Расходы", values)), ReportFormatting.unit(yearRows));
    }

    private static double sumAbs(List<SpendingByCategoryRow> rows) {
        BigDecimal sum = BigDecimal.ZERO;
        for (SpendingByCategoryRow r : rows) {
            sum = sum.add(ReportFormatting.magnitude(r));
        }
        return sum.doubleValue();
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "year-report SKILL.md not loaded — check skills-classpath"));
    }

    /** One month's total spend for the trend line (JSON-serialised into the LLM gather context). */
    public record MonthTotal(String month, double total) {
    }
}
