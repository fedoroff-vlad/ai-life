package dev.fedorov.ailife.contracts.weather;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One calendar month's climate normal, an entry of {@link ClimateNormals}. {@code month} is 1–12;
 * {@code avgTempC} is the average daily-mean temperature (°C) for that month over the reference
 * period; {@code precipMm} is the average monthly precipitation total (mm). {@code avgTempC}/
 * {@code precipMm} stay null when the source has no data for the month (not an error). This is the
 * season substrate the travel-agent reads to judge a destination's fit for a given month.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MonthlyNormal(
        int month,
        Double avgTempC,
        Double precipMm) {
}
