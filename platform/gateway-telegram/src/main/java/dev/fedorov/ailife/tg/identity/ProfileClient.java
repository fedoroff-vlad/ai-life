package dev.fedorov.ailife.tg.identity;

import dev.fedorov.ailife.contracts.profile.HouseholdDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class ProfileClient {

    private final WebClient http;

    public ProfileClient(WebClient profileWebClient) {
        this.http = profileWebClient;
    }

    public Mono<UserDto> findByTelegramId(long telegramUserId) {
        return http.get()
                .uri("/v1/users/by-telegram/{id}", telegramUserId)
                .retrieve()
                .bodyToMono(UserDto.class)
                .onErrorResume(WebClientResponseException.class, ex ->
                        ex.getStatusCode() == HttpStatus.NOT_FOUND ? Mono.empty() : Mono.error(ex));
    }

    public Mono<HouseholdDto> createHousehold(String name) {
        return http.post()
                .uri("/v1/households")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", name))
                .retrieve()
                .bodyToMono(HouseholdDto.class);
    }

    public Mono<UserDto> createUser(String householdId, String displayName, long telegramUserId,
                                    String locale, String role) {
        return http.post()
                .uri("/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "householdId", householdId,
                        "displayName", displayName,
                        "telegramUserId", telegramUserId,
                        "locale", locale,
                        "role", role))
                .retrieve()
                .bodyToMono(UserDto.class);
    }
}
