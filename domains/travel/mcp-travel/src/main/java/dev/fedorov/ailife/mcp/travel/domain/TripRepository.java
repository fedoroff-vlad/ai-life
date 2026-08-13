package dev.fedorov.ailife.mcp.travel.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    /** Tenant-scoped read: a trip is only visible within its own household (travel.md §Trip wallet). */
    Optional<Trip> findByIdAndHouseholdId(UUID id, UUID householdId);
}
