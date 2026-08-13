package dev.fedorov.ailife.mcp.travel.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripExpenseRepository extends JpaRepository<TripExpense, UUID> {

    List<TripExpense> findByTripIdOrderBySpentAt(UUID tripId);
}
