package dev.fedorov.ailife.contracts.weather;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Monthly climate normals for one location, returned by the {@code mcp-weather} {@code climate} tool
 * (TR-a). {@code months} holds one {@link MonthlyNormal} per calendar month (12 entries, ordered
 * January→December) when the {@code climate} call names no specific month, or a single entry when it
 * does. The list is <b>empty</b> when the source is unreachable or has no data — callers degrade
 * rather than error (the tool never surfaces a 500). Read-only climatology DATA; judging a season fit
 * from it is the calling agent's job. This is the travel-agent's season substrate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClimateNormals(
        Double latitude,
        Double longitude,
        List<MonthlyNormal> months) {
}
