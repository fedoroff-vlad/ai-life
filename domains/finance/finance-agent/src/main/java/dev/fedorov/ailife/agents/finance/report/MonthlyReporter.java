package dev.fedorov.ailife.agents.finance.report;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.SpendingClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.docrender.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>No spending this month → a friendly invite (no LLM call, no board). A
 * render/store failure still hands back the textual narrative; any other failure
 * degrades to a friendly message. Charts ride in later with the deferred shared
 * {@code chart-render} capability (see finance.md "recorded vision").
 */
@Component
public class MonthlyReporter {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReporter.class);
    private static final String SKILL_NAME = "monthly-report";

    /** Russian month names for the deterministic report header (owner-facing). */
    private static final String[] MONTHS_RU = {
            "январь", "февраль", "март", "апрель", "май", "июнь",
            "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
    };

    private final Coordinator coordinator;
    private final SpendingClient spending;
    private final DeliverablePublisher publisher;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public MonthlyReporter(Coordinator coordinator,
                           SpendingClient spending,
                           DeliverablePublisher publisher,
                           SkillRegistry skills,
                           AgentManifest manifest,
                           ObjectMapper json) {
        this.coordinator = coordinator;
        this.spending = spending;
        this.publisher = publisher;
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
        String monthLabel = MONTHS_RU[nowZ.getMonthValue() - 1] + " " + nowZ.getYear();

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

    /** Render the report board (narrative + deterministic breakdown), store it, return the public link. */
    private Mono<String> store(NormalizedMessage msg, String narrative, List<SpendingByCategoryRow> rows, String monthLabel) {
        Doc doc = Doc.builder("Финансовый отчёт")
                .kicker("Финансы · Отчёт за месяц")
                .subtitle(monthLabel + " · " + totalsLine(rows))
                .section("Сводка", DeliverablePublisher.splitParagraphs(narrative))
                .section("Расходы по категориям", categoryLines(rows))
                .build();
        return publisher.publish(msg.householdId(), msg.userId(), doc);
    }

    /** One line per category, biggest first: "Категория — 1234.50 RUB · 12 операций". */
    private List<String> categoryLines(List<SpendingByCategoryRow> rows) {
        List<String> lines = new ArrayList<>();
        rows.stream()
                .sorted(Comparator.comparing(MonthlyReporter::magnitude).reversed())
                .forEach(r -> lines.add(
                        name(r.categoryName()) + " — " + amount(r.spent()) + " " + currency(r.currency())
                                + " · " + r.txCount() + " " + opsWord(r.txCount())));
        return lines;
    }

    /** Total spend per currency, e.g. "12 340.00 RUB, 56.00 USD" (currencies are never summed together). */
    private String totalsLine(List<SpendingByCategoryRow> rows) {
        Map<String, BigDecimal> perCurrency = new LinkedHashMap<>();
        for (SpendingByCategoryRow r : rows) {
            perCurrency.merge(currency(r.currency()), magnitude(r), BigDecimal::add);
        }
        List<String> parts = new ArrayList<>();
        perCurrency.forEach((cur, sum) -> parts.add(amount(sum) + " " + cur));
        return String.join(", ", parts);
    }

    private static BigDecimal magnitude(SpendingByCategoryRow r) {
        return r.spent() == null ? BigDecimal.ZERO : r.spent().abs();
    }

    private static String amount(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).toPlainString();
    }

    private static String currency(String c) {
        return c == null || c.isBlank() ? "?" : c;
    }

    private static String name(String n) {
        return n == null || n.isBlank() ? "Без категории" : n;
    }

    /** Russian plural for "операция" (1 операция / 2 операции / 5 операций). */
    private static String opsWord(long n) {
        long mod100 = n % 100;
        long mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "операций";
        if (mod10 == 1) return "операция";
        if (mod10 >= 2 && mod10 <= 4) return "операции";
        return "операций";
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
