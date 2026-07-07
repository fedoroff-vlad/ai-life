package dev.fedorov.ailife.agents.finance.report;

import dev.fedorov.ailife.contracts.chart.ChartSeries;
import dev.fedorov.ailife.contracts.chart.ChartSpec;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, stateless formatting helpers shared by the finance report deliverables
 * ({@link MonthlyReporter}, {@link YearReporter}) — the deterministic breakdown lines, per-currency
 * totals, and the spending-by-category bar-chart spec, plus the small money/label formatters they
 * all lean on. Lifted here when {@code YearReporter} became the second consumer (the repo's
 * "generalise on the second copy" rule). No Spring, no I/O — just data → strings/specs.
 */
final class ReportFormatting {

    private ReportFormatting() {
    }

    /** Russian month names for report headers / trend labels (owner-facing). */
    static final String[] MONTHS_RU = {
            "январь", "февраль", "март", "апрель", "май", "июнь",
            "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"
    };

    /** Short Russian month names for a compact trend x-axis. */
    static final String[] MONTHS_RU_SHORT = {
            "янв", "фев", "мар", "апр", "май", "июн",
            "июл", "авг", "сен", "окт", "ноя", "дек"
    };

    /**
     * Build a bar-chart spec of the top spending categories (biggest first), capped at {@code maxCats};
     * null when there's nothing positive to plot. {@code title} lets each reporter label its period.
     */
    static ChartSpec categoryChartSpec(List<SpendingByCategoryRow> rows, String title, int maxCats) {
        List<SpendingByCategoryRow> top = rows.stream()
                .filter(r -> magnitude(r).signum() > 0)
                .sorted(Comparator.comparing(ReportFormatting::magnitude).reversed())
                .limit(maxCats)
                .toList();
        if (top.isEmpty()) {
            return null;
        }
        List<String> categories = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (SpendingByCategoryRow r : top) {
            categories.add(name(r.categoryName()));
            values.add(magnitude(r).doubleValue());
        }
        return new ChartSpec("bar", title, categories,
                List.of(new ChartSeries("Расходы", values)), unit(top));
    }

    /** The shared currency for a chart's value axis, or null when the rows mix currencies. */
    static String unit(List<SpendingByCategoryRow> rows) {
        String only = null;
        for (SpendingByCategoryRow r : rows) {
            String cur = currency(r.currency());
            if (only == null) {
                only = cur;
            } else if (!only.equals(cur)) {
                return null; // mixed currencies — omit the unit rather than mislabel
            }
        }
        return only;
    }

    /** One line per category, biggest first: "Категория — 1234.50 RUB · 12 операций". */
    static List<String> categoryLines(List<SpendingByCategoryRow> rows) {
        List<String> lines = new ArrayList<>();
        rows.stream()
                .sorted(Comparator.comparing(ReportFormatting::magnitude).reversed())
                .forEach(r -> lines.add(
                        name(r.categoryName()) + " — " + amount(r.spent()) + " " + currency(r.currency())
                                + " · " + r.txCount() + " " + opsWord(r.txCount())));
        return lines;
    }

    /** Total spend per currency, e.g. "12 340.00 RUB, 56.00 USD" (currencies are never summed together). */
    static String totalsLine(List<SpendingByCategoryRow> rows) {
        Map<String, BigDecimal> perCurrency = new LinkedHashMap<>();
        for (SpendingByCategoryRow r : rows) {
            perCurrency.merge(currency(r.currency()), magnitude(r), BigDecimal::add);
        }
        List<String> parts = new ArrayList<>();
        perCurrency.forEach((cur, sum) -> parts.add(amount(sum) + " " + cur));
        return String.join(", ", parts);
    }

    static BigDecimal magnitude(SpendingByCategoryRow r) {
        return r.spent() == null ? BigDecimal.ZERO : r.spent().abs();
    }

    static String amount(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).toPlainString();
    }

    static String currency(String c) {
        return c == null || c.isBlank() ? "?" : c;
    }

    static String name(String n) {
        return n == null || n.isBlank() ? "Без категории" : n;
    }

    /** Russian plural for "операция" (1 операция / 2 операции / 5 операций). */
    static String opsWord(long n) {
        long mod100 = n % 100;
        long mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "операций";
        if (mod10 == 1) return "операция";
        if (mod10 >= 2 && mod10 <= 4) return "операции";
        return "операций";
    }
}
