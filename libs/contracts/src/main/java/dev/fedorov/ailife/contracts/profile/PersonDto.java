package dev.fedorov.ailife.contracts.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * A person known to a household — contact for birthdays, gifts, greetings.
 * Not a {@link UserDto} (those are bot operators). {@code interests} is an
 * arbitrary JSON array; {@code leadDaysOverride} is an object like
 * {@code {"gift": 30, "greeting": 1}} or {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonDto(
        UUID id,
        UUID householdId,
        String displayName,
        String relationship,
        String locale,
        JsonNode interests,
        String notes,
        JsonNode leadDaysOverride,
        Instant createdAt) {
}
