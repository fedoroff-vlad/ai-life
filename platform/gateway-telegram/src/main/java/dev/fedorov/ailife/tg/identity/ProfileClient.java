package dev.fedorov.ailife.tg.identity;

import dev.fedorov.ailife.contracts.profile.HouseholdDto;
import dev.fedorov.ailife.contracts.profile.HouseholdInviteDto;
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

    /** Fetch a user by id — used to resolve the inviter's Telegram id for the join notification. */
    public Mono<UserDto> findById(String userId) {
        return http.get()
                .uri("/v1/users/{id}", userId)
                .retrieve()
                .bodyToMono(UserDto.class)
                .onErrorResume(WebClientResponseException.class, ex ->
                        ex.getStatusCode() == HttpStatus.NOT_FOUND ? Mono.empty() : Mono.error(ex));
    }

    /**
     * Redeem a pending family invite for the given (just-resolved) invitee. profile-service inserts
     * the invitee's {@code household_members} row and flips the invite to {@code accepted}. Surfaces
     * the server's error status (404 unknown / 409 already-used / 422 unknown invitee) as a
     * {@link WebClientResponseException} for the caller to turn into a graceful reply.
     */
    public Mono<HouseholdInviteDto> redeem(String token, String inviteeUserId) {
        return http.post()
                .uri("/v1/invites/by-token/{token}/redeem", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("inviteeUserId", inviteeUserId))
                .retrieve()
                .bodyToMono(HouseholdInviteDto.class);
    }

    /**
     * Mint a pre-authorized family invite (ADR-0001, slice 4b-ii): the owner invites someone into
     * their {@code familyHouseholdId} tagged {@code relationship}. profile-service returns the invite
     * with its {@code token}, which the gateway turns into a {@code /start <token>} deep-link.
     * {@code grantSharedAccess} defaults to true server-side.
     */
    public Mono<HouseholdInviteDto> mintInvite(String familyHouseholdId, String inviterUserId,
                                               String relationship) {
        return http.post()
                .uri("/v1/invites")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "familyHouseholdId", familyHouseholdId,
                        "inviterUserId", inviterUserId,
                        "relationship", relationship))
                .retrieve()
                .bodyToMono(HouseholdInviteDto.class);
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
