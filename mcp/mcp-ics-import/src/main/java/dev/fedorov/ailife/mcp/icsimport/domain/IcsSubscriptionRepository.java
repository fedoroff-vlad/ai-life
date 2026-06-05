package dev.fedorov.ailife.mcp.icsimport.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IcsSubscriptionRepository extends JpaRepository<IcsSubscription, UUID> {

    List<IcsSubscription> findByHouseholdIdOrderByName(UUID householdId);

    Optional<IcsSubscription> findByHouseholdIdAndSlug(UUID householdId, String slug);
}
