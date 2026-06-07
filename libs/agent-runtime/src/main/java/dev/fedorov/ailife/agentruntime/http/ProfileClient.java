package dev.fedorov.ailife.agentruntime.http;

import dev.fedorov.ailife.contracts.profile.PersonDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Shared profile-service client for agents. The {@link WebClient} bean named
 * {@code profileServiceWebClient} is owned by each agent (it binds the base URL
 * from per-agent properties); this class is purely the request shape.
 *
 * <p>{@link #personById(UUID)} is calendar-only today (finance has no people),
 * kept on the shared client because the cost is zero and a third agent may
 * need it. {@link #usersByHousehold(UUID)} powers the trigger fan-out flow
 * both agents already share.
 */
public class ProfileClient {

    private static final ParameterizedTypeReference<List<UserDto>> USER_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;

    public ProfileClient(@Qualifier("profileServiceWebClient") WebClient http) {
        this.http = http;
    }

    /** Empty stream if no users found (also when the household is unknown). */
    public Flux<UserDto> usersByHousehold(UUID householdId) {
        return http.get()
                .uri("/v1/users/by-household/{id}", householdId)
                .retrieve()
                .bodyToMono(USER_LIST)
                .defaultIfEmpty(List.of())
                .flatMapMany(Flux::fromIterable);
    }

    /** Convenience for tests / single-shot callers. */
    public Mono<List<UserDto>> usersByHouseholdList(UUID householdId) {
        return usersByHousehold(householdId).collectList();
    }

    /**
     * Returns the person row or {@link Mono#empty()} if profile-service responds
     * 404. Other errors propagate so the caller can decide whether to swallow
     * them — the trigger flow swallows them and falls back to running the skill
     * with no person data.
     */
    public Mono<PersonDto> personById(UUID personId) {
        return http.get()
                .uri("/v1/people/{id}", personId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp -> Mono.empty())
                .bodyToMono(PersonDto.class);
    }
}
