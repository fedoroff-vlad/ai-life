package dev.fedorov.ailife.profile.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTelegramUserId(Long telegramUserId);

    List<User> findByHouseholdIdOrderByDisplayName(UUID householdId);
}
