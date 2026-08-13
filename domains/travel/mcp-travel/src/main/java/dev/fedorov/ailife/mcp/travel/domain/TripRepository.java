package dev.fedorov.ailife.mcp.travel.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    /** Tenant-scoped read: a trip is only visible within its own household (travel.md §Trip wallet). */
    Optional<Trip> findByIdAndHouseholdId(UUID id, UUID householdId);

    /**
     * The household's active trip: the most recently created trip that isn't {@code closed}. The
     * wallet flow (EX-b) uses it to attach fund/exchange/spend/tally to "the current trip" without the
     * owner restating which one (single-trip conversation model; multi-trip disambiguation is later).
     */
    Optional<Trip> findFirstByHouseholdIdAndStatusNotOrderByCreatedAtDesc(UUID householdId, String status);
}
