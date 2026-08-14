package dev.fedorov.ailife.mcp.travel.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RouteRepository extends JpaRepository<Route, UUID> {

    /** Tenant-scoped read: a route is only visible within its own household. */
    Optional<Route> findByIdAndHouseholdId(UUID id, UUID householdId);

    /** All routes for a household, newest import first. */
    List<Route> findByHouseholdIdOrderByImportedAtDesc(UUID householdId);

    /** Routes attached to a specific trip within a household, newest import first. */
    List<Route> findByHouseholdIdAndTripIdOrderByImportedAtDesc(UUID householdId, UUID tripId);
}
