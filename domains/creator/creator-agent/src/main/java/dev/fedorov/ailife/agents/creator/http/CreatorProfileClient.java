package dev.fedorov.ailife.agents.creator.http;

import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
import dev.fedorov.ailife.contracts.creator.SetCreatorProfileInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Calls the {@code mcp-creator} domain-MCP's {@code POST /internal/creator-profile} passthrough
 * (CR-c1) to upsert a person's creator track. The creator-profiler flow has already extracted a
 * concrete {@link SetCreatorProfileInput} from a typed niche/audience/tone message, so it writes
 * deterministically over HTTP rather than through an LLM-driven MCP tool call (the MCP/SSE binding
 * stays for future selection but isn't MockWebServer-testable). Mirrors mcp-nutrition's
 * {@code DietProfileClient}.
 */
@Component
public class CreatorProfileClient {

    private final WebClient http;

    public CreatorProfileClient(@Qualifier("mcpCreatorWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<CreatorProfileDto> set(SetCreatorProfileInput input) {
        return http.post()
                .uri("/internal/creator-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(CreatorProfileDto.class)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Read a person's creator track (null ownerId = household-default). A 404 (none set yet) maps to
     * an empty Mono, so the trend gather just omits the track constraint.
     */
    public Mono<CreatorProfileDto> get(UUID householdId, UUID ownerId) {
        return http.get()
                .uri(b -> {
                    b.path("/internal/creator-profile").queryParam("householdId", householdId);
                    if (ownerId != null) b.queryParam("ownerId", ownerId);
                    return b.build();
                })
                .retrieve()
                .bodyToMono(CreatorProfileDto.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty());
    }
}
