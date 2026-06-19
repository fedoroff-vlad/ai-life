package dev.fedorov.ailife.memory.http;

import dev.fedorov.ailife.contracts.profile.PersonDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.UUID;

/**
 * Thin profile-service read client for relation capture (memory-from-chat / MFC-c):
 * resolves a person's name to a {@code core.people} UUID so an extracted edge can
 * be anchored on a real person. Best-effort — any failure (profile-service down,
 * no match) yields {@code null}, so capture degrades to dropping that edge rather
 * than throwing.
 */
@Component
public class ProfileClient {

    private static final Logger log = LoggerFactory.getLogger(ProfileClient.class);

    private static final ParameterizedTypeReference<List<PersonDto>> PERSON_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;

    public ProfileClient(@Qualifier("profileWebClient") WebClient http) {
        this.http = http;
    }

    /**
     * First person in the household whose {@code displayName} matches the label
     * (case-insensitive, trimmed), or {@code null} if there is no match or the
     * lookup fails. We deliberately do NOT auto-create a person from a chat-derived
     * label — a hallucinated name would pollute {@code core.people}; unresolved
     * subjects are simply skipped (MVP).
     */
    public UUID resolvePersonId(UUID householdId, String label) {
        if (householdId == null || label == null || label.isBlank()) {
            return null;
        }
        String target = label.trim();
        try {
            List<PersonDto> people = http.get()
                    .uri("/v1/people/by-household/{id}", householdId)
                    .retrieve()
                    .bodyToMono(PERSON_LIST)
                    .defaultIfEmpty(List.of())
                    .block();
            if (people == null) {
                return null;
            }
            return people.stream()
                    .filter(p -> target.equalsIgnoreCase(p.displayName()))
                    .map(PersonDto::id)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("person resolution failed for '{}': {}", target, e.toString());
            return null;
        }
    }
}
