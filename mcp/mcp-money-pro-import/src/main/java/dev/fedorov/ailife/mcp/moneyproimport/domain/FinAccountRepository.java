package dev.fedorov.ailife.mcp.moneyproimport.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FinAccountRepository extends JpaRepository<FinAccount, UUID> {
}
