package dev.fedorov.ailife.agents.calendar.http;

import dev.fedorov.ailife.contracts.profile.PersonDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
public class ProfileClient {

    private static final ParameterizedTypeReference<List<UserDto>> USER_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;

    public ProfileClient(@Qualifier("profileServiceWebClient") WebClient http) {
        this.http = http;
    }

    /** Empty list if no users found (also when the household is unknown). */
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
