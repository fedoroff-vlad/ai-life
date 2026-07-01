package dev.fedorov.ailife.agents.briefing.http;

import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Calls the {@code mcp-briefing} domain-MCP's {@code POST /internal/briefing-profile} passthrough to
 * upsert a person's briefing preferences. The briefing-profiler flow has already extracted a concrete
 * {@link SetBriefingProfileInput} (and geocoded the city), so it writes deterministically over HTTP
 * rather than through an LLM-driven MCP tool call. Mirrors creator-agent's {@code CreatorProfileClient}.
 */
@Component
public class BriefingProfileClient {

    private final WebClient http;

    public BriefingProfileClient(@Qualifier("mcpBriefingWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<BriefingProfileDto> set(SetBriefingProfileInput input) {
        return http.post()
                .uri("/internal/briefing-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(BriefingProfileDto.class)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Read a person's briefing prefs (null ownerId = household-default). A 404 (none set yet) maps to
     * an empty Mono, so the digest flow just falls back to defaults.
     */
    public Mono<BriefingProfileDto> get(UUID householdId, UUID ownerId) {
        return http.get()
                .uri(b -> {
                    b.path("/internal/briefing-profile").queryParam("householdId", householdId);
                    if (ownerId != null) b.queryParam("ownerId", ownerId);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(BriefingProfileDto.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty());
    }
}
