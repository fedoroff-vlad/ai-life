package dev.fedorov.ailife.contracts.chart;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A data-only description of a chart to render — the presentation-agnostic input to the
 * {@code mcp-chart-render} capability. The caller (a finance report, a briefing digest, …) provides
 * the numbers and labels; the capability turns them into an image.
 *
 * <ul>
 *   <li>{@code type} — {@code bar} | {@code line} | {@code pie} (unknown/blank falls back to bar).</li>
 *   <li>{@code categories} — x-axis labels for bar/line, slice labels for pie; align 1:1 with each
 *       series' {@code values}.</li>
 *   <li>{@code series} — one or more named series (multi-series bar/line get a legend); pie uses only
 *       the first.</li>
 *   <li>{@code unit} — optional value suffix shown on labels/axis (e.g. {@code "₽"}, {@code "USD"}).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartSpec(
        String type,
        String title,
        List<String> categories,
        List<ChartSeries> series,
        String unit) {
}
