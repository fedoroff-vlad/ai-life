package dev.fedorov.ailife.contracts.profile;

import java.time.Instant;
import java.util.UUID;

public record HouseholdDto(UUID id, String name, Instant createdAt) {
}
