package dev.fedorov.ailife.mcp.travel.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TripMemberRepository extends JpaRepository<TripMember, UUID> {

    List<TripMember> findByTripIdOrderByCreatedAt(UUID tripId);
}
