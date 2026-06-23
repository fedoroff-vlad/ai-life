package dev.fedorov.ailife.mcp.nutrition.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BasketRepository extends JpaRepository<Basket, UUID> {

    /** Analysed baskets in a household, most recently captured first (Pageable caps the count). */
    List<Basket> findByHouseholdIdOrderByCapturedAtDesc(UUID householdId, Pageable pageable);
}
