package dev.fedorov.ailife.agents.nutritionist.http;

import dev.fedorov.ailife.contracts.media.CaptionInput;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the shared {@code mcp-media-processing} capability's {@code POST /internal/caption}
 * passthrough to run a vision caption over a stored image. The food-log flow uses this to turn a
 * meal photo into a structured entry — the vision call lives once, in the capability-MCP, so the
 * nutritionist reuses it rather than embedding the {@code vision} channel here (same as finance's
 * receipt-parser and stylist's cataloguer).
 *
 * <p>The capability is also bound over MCP/SSE (for future LLM-driven tool selection), but this
 * deterministic call goes over the HTTP passthrough, which (unlike MCP/SSE) is MockWebServer-testable.
 * Generous timeout: a vision round-trip can be slow.
 */
@Component
public class CaptionClient {

    private final WebClient http;

    public CaptionClient(@Qualifier("mcpMediaProcessingWebClient") WebClient http) {
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
