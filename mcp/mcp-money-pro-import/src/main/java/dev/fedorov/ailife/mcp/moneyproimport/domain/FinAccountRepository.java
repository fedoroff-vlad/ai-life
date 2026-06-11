package dev.fedorov.ailife.mcp.moneyproimport.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FinAccountRepository extends JpaRepository<FinAccount, UUID> {

    List<FinAccount> findByHouseholdId(UUID householdId);
}
