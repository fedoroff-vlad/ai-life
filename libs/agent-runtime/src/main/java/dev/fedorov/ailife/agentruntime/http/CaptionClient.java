package dev.fedorov.ailife.agentruntime.http;

import dev.fedorov.ailife.contracts.media.CaptionInput;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Shared client for the {@code mcp-media-processing} capability's {@code POST /internal/caption}
 * passthrough: vision-caption a stored media object ({@code mediaId}) under an optional instruction,
 * returning the model's description. The deterministic, MockWebServer-testable path (MCP/SSE can't be
 * MockWebServer'd). A vision pass is slow, so a 30s timeout.
 *
 * <p>Lives in {@code agent-runtime} because more than one agent captions media (finance receipts,
 * nutrition food photos, stylist wardrobe items): a capability HTTP client is shared code, not a
 * per-agent copy. Opt-in — a consuming agent declares the {@code @Bean} in its {@code OutboundHttpConfig},
 * passing a {@code WebClient} pointed at {@code mcp-media-processing}.
 */
public class CaptionClient {

    private final WebClient http;

    public CaptionClient(WebClient http) {
        this.http = http;
    }

    public Mono<CaptionResult> caption(String mediaId, String instruction) {
        return http.post()
                .uri("/internal/caption")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CaptionInput(mediaId, instruction))
                .retrieve()
                .bodyToMono(CaptionResult.class)
                .timeout(Duration.ofSeconds(30));
    }
}
