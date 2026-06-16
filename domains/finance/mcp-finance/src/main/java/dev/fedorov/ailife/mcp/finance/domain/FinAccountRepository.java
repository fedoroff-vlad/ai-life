package dev.fedorov.ailife.mcp.finance.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FinAccountRepository extends JpaRepository<FinAccount, UUID> {

    List<FinAccount> findByHouseholdIdOrderByName(UUID householdId);
}
