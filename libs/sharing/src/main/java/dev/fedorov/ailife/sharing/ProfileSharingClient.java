package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The one thin identity read the sharing capability needs, shared by every consumer (ADR-0002). It wraps
 * profile-service's two tenant endpoints so neither the agents' write path nor the web services' read path
 * re-implement them:
 * <ul>
 *   <li>{@link #householdRouting(UUID)} — the <b>write-path split</b> ({@code personalHouseholdId} +
 *       {@code sharedHouseholdIds}) that {@link SharingResolver} routes a create against.</li>
 *   <li>{@link #households(UUID)} — the <b>read-path union</b> (personal ∪ shared) a domain reads its
 *       "own + shared" rows across.</li>
 * </ul>
 *
 * <p>The {@link WebClient} is owned by the consumer (it binds the profile-service base URL from its own
 * properties); this class is purely the request shape, mirroring {@code agent-runtime}'s {@code ProfileClient}
 * and calendar-web's former {@code ProfileHouseholdsClient} (both collapse into this one).
 *
 * <p>Failure semantics match the callers they replace: a 4xx (unknown user) on the routing read resolves
 * to {@link Mono#empty()} so the write path falls back to its envelope household; any failure on the union
 * read resolves to an empty list so a read never breaks on a profile hiccup.
 */
public class ProfileSharingClient {

    private static final ParameterizedTypeReference<List<UUID>> UUID_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;

    public ProfileSharingClient(WebClient profileServiceWebClient) {
        this.http = profileServiceWebClient;
    }

    /**
     * The caller's tenant-routing split (personal vs shared households). {@link Mono#empty()} on 4xx
     * (unknown user) so the write path falls back to the envelope household.
     */
    public Mono<HouseholdRoutingDto> householdRouting(UUID userId) {
        return http.get()
                .uri("/v1/users/{id}/household-routing", userId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp -> Mono.empty())
                .bodyToMono(HouseholdRoutingDto.class);
    }

    /**
     * The caller's household set (personal ∪ shared) for the read-path union. Any error (404 unknown
     * user, profile down) resolves to an empty list so the read falls back to its own household.
     */
    public Mono<List<UUID>> households(UUID userId) {
        return http.get()
                .uri("/v1/users/{id}/households", userId)
                .retrieve()
                .bodyToMono(UUID_LIST)
                .onErrorResume(e -> Mono.just(List.of()));
    }
}
