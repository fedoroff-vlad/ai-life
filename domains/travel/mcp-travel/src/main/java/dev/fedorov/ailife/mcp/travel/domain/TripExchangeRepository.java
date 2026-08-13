package dev.fedorov.ailife.mcp.travel.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripExchangeRepository extends JpaRepository<TripExchange, UUID> {

    List<TripExchange> findByTripIdOrderByExchangedAt(UUID tripId);
}
