package dev.fedorov.ailife.mcp.travel.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripFundingRepository extends JpaRepository<TripFunding, UUID> {

    List<TripFunding> findByTripIdOrderByAcquiredAt(UUID tripId);
}
