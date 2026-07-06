package dev.fedorov.ailife.contracts.chart;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One named data series in a {@link ChartSpec}. {@code values} align 1:1 with the spec's
 * {@code categories} (same length, same order); a null entry is a gap. For a {@code pie} chart only
 * the first series is used — its values are the slice sizes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChartSeries(
        String name,
        List<Double> values) {
}
