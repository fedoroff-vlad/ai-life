package dev.fedorov.ailife.calendarweb.http;

import dev.fedorov.ailife.calendarweb.config.CalendarWebProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Resolves a feed owner (a {@code core.users} id) to the member's household set (personal ∪ shared) via
 * profile-service's {@code GET /v1/users/{id}/households} — the per-person ICS feed reads the union over
 * this set (ADR-0001 slice 5 / #295). calendar-web owns no identity schema; this is the deterministic
 * inter-service read. Any error (404 unknown user, profile down) resolves to an empty list so the caller
 * falls back to the feed's own household — the feed never breaks on a profile hiccup.
 */
@Component
public class ProfileHouseholdsClient {

    private static final ParameterizedTypeReference<List<UUID>> UUID_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;

    public ProfileHouseholdsClient(WebClient.Builder builder, CalendarWebProperties props) {
        this.http = builder.baseUrl(props.getProfileServiceUrl()).build();
    }

    /** The user's household set, or an empty list on any failure (caller falls back to the feed household). */
    public Mono<List<UUID>> households(UUID userId) {
        return http.get()
                .uri("/v1/users/{id}/households", userId)
                .retrieve()
                .bodyToMono(UUID_LIST)
                .onErrorResume(e -> Mono.just(List.of()));
    }
}
