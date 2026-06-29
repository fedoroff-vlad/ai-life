package dev.fedorov.ailife.agents.calendar.http;

import dev.fedorov.ailife.contracts.calendar.CalendarFeedDto;
import dev.fedorov.ailife.contracts.calendar.CreateFeedInput;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mints / finds read-only ICS feeds via mcp-caldav's {@code /internal/feeds} (#195). Used by the
 * agent to <b>ensure</b> a user has a feed on their first calendar message — list the household's feeds,
 * reuse an active one owned by the user, else mint a new one. Deterministic HTTP, no LLM.
 */
@Component
public class CaldavFeedClient {

    private static final ParameterizedTypeReference<List<CalendarFeedDto>> FEED_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;

    public CaldavFeedClient(WebClient mcpCaldavWebClient) {
        this.http = mcpCaldavWebClient;
    }

    /**
     * Return the user's active feed, or mint one if none exists yet. {@link Ensured#created()} tells the
     * caller whether to surface the subscribe link (only on the first time).
     */
    public Mono<Ensured> ensureFeed(UUID householdId, UUID ownerId, String label) {
        return http.get()
                .uri(b -> b.path("/internal/feeds").queryParam("householdId", householdId).build())
                .retrieve()
                .bodyToMono(FEED_LIST)
                .flatMap(feeds -> feeds.stream()
                        .filter(f -> f.revokedAt() == null && Objects.equals(f.ownerId(), ownerId))
                        .findFirst()
                        .map(existing -> Mono.just(new Ensured(existing, false)))
                        .orElseGet(() -> mint(householdId, ownerId, label).map(f -> new Ensured(f, true))));
    }

    private Mono<CalendarFeedDto> mint(UUID householdId, UUID ownerId, String label) {
        return http.post()
                .uri("/internal/feeds")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateFeedInput(householdId, ownerId, label))
                .retrieve()
                .bodyToMono(CalendarFeedDto.class);
    }

    /** A feed plus whether it was just minted (first time for this user). */
    public record Ensured(CalendarFeedDto feed, boolean created) {
    }
}
