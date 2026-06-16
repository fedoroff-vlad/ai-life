package dev.fedorov.ailife.mcp.moneyproimport.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FinTransactionRepository extends JpaRepository<FinTransaction, UUID> {

    boolean existsByHouseholdIdAndSourceAndExternalRef(UUID householdId, String source, String externalRef);
}
