package dev.fedorov.ailife.profile.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PersonRepository extends JpaRepository<Person, UUID> {

    List<Person> findByHouseholdIdOrderByDisplayName(UUID householdId);
}
