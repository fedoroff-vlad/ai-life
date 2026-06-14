package dev.fedorov.ailife.conversation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationStateRepository extends JpaRepository<ConversationState, UUID> {

    Optional<ConversationState> findByHouseholdIdAndUserIdAndChannel(
            UUID householdId, UUID userId, String channel);
}
