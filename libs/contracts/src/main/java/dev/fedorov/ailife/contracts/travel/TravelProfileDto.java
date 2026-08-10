package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A person's travel preferences — one per (household, owner). Mirrors a {@code travel.travel_profile}
 * row; a null {@code ownerId} is the household-default. It personalizes the on-demand trip planner:
 * {@code homeBaseLabel}/{@code homeBaseLatitude}/{@code homeBaseLongitude} are where trips depart from
 * (the travel-profiler geocodes a stated city into lat/lon); {@code restTypes} is a JSON array of
 * preferred vacation kinds ({@code beach|active|family|couple|city|ski|wellness}); {@code companions}
 * is {@code solo|couple|family}; {@code childAges} is an optional JSON array of ints;
 * {@code budgetAmount}/{@code budgetCurrency} are a soft budget hint (the live budget check still
 * comes from the finance brief). Absent fields stay null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TravelProfileDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String homeBaseLabel,
        Double homeBaseLatitude,
        Double homeBaseLongitude,
        JsonNode restTypes,
        String companions,
        JsonNode childAges,
        BigDecimal budgetAmount,
        String budgetCurrency,
        String notes,
        Instant updatedAt) {
}
