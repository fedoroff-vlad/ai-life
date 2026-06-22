package dev.fedorov.ailife.agents.stylist.http;

import dev.fedorov.ailife.contracts.imagegen.ImageGenInput;
import dev.fedorov.ailife.contracts.imagegen.ImageGenResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Calls the shared {@code mcp-image-gen} capability's {@code POST /internal/generate} passthrough —
 * the board flows use it to render an editorial illustration from a text prompt. The deterministic,
 * MockWebServer-testable path (MCP/SSE can't be MockWebServer'd). The engine behind the capability is
 * swappable by config (a placeholder stub now, a self-hosted GPU model later) with no change here.
 * Mirrors the finance agent's {@code MarketDataClient}.
 *
 * <p>The timeout is generous because a real model can take many seconds to render; with the stub it
 * returns instantly. Callers soft-fail (an illustration is decoration, not the deliverable).
 */
@Component
public class ImageGenClient {

    private final WebClient http;

    public ImageGenClient(@Qualifier("mcpImageGenWebClient") WebClient http) {
        this.http = http;
    }

    /** Text-to-image (no reference photos). {@code ownerId} scopes the stored result. */
    public Mono<ImageGenResult> generate(UUID householdId, UUID ownerId, String prompt) {
        return generate(householdId, ownerId, prompt, null);
    }

    public Mono<ImageGenResult> generate(UUID householdId, UUID ownerId, String prompt, List<UUID> refMediaIds) {
        return http.post()
                .uri("/internal/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ImageGenInput(householdId, ownerId, prompt, refMediaIds))
                .retrieve()
                .bodyToMono(ImageGenResult.class)
                .timeout(java.time.Duration.ofSeconds(60));
    }
}
