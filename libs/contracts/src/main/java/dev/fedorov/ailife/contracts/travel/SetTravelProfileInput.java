package dev.fedorov.ailife.contracts.travel;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Upsert a person's travel preferences. Keyed on (householdId, ownerId) — a null ownerId is the
 * household-default. {@code householdId} is required; every other field is applied as given (a full
 * set, not a partial merge — the travel-profiler flow recomputes the whole profile from the stated
 * preferences). {@code homeBaseLatitude}/{@code homeBaseLongitude} are the geocoded coordinates of
 * {@code homeBaseLabel} (the agent geocodes the stated city before calling); {@code restTypes} is a
 * free-form JSON array of preferred vacation kinds; {@code companions} is {@code solo|couple|family};
 * {@code childAges} is an optional JSON array of ints; {@code budgetAmount}/{@code budgetCurrency}
 * are the soft budget hint. Vocabulary enforcement lives in the profiler, not this store.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SetTravelProfileInput(
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
        String notes) {
}
