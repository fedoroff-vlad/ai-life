package dev.fedorov.ailife.agents.travel.http;

import dev.fedorov.ailife.contracts.travel.SetTravelProfileInput;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Calls the {@code mcp-travel} domain-MCP's {@code POST /internal/travel-profile} passthrough to upsert
 * a person's travel preferences. The travel-profiler flow has already extracted a concrete
 * {@link SetTravelProfileInput} (and geocoded the home-base city), so it writes deterministically over
 * HTTP rather than through an LLM-driven MCP tool call. Mirrors briefing-agent's
 * {@code BriefingProfileClient}.
 */
@Component
public class TravelProfileClient {

    private final WebClient http;

    public TravelProfileClient(@Qualifier("mcpTravelWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<TravelProfileDto> set(SetTravelProfileInput input) {
        return http.post()
                .uri("/internal/travel-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(TravelProfileDto.class)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Read a person's travel prefs (null ownerId = household-default). A 204 (none set yet) maps to an
     * empty Mono, so the caller falls back to the household default / empty-profile default.
     */
    public Mono<TravelProfileDto> get(UUID householdId, UUID ownerId) {
        return http.get()
                .uri(b -> {
                    b.path("/internal/travel-profile").queryParam("householdId", householdId);
                    if (ownerId != null) b.queryParam("ownerId", ownerId);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(TravelProfileDto.class)
                .timeout(Duration.ofSeconds(10));
    }
}
