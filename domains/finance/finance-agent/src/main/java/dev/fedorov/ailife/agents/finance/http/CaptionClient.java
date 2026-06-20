package dev.fedorov.ailife.agents.finance.http;

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
 * passthrough (MP-c1) to run a vision caption over a stored image. {@code receipt-parser} uses
 * this instead of embedding the {@code vision} channel call itself — the vision call now lives
 * once, in the capability-MCP, so any agent reuses it (MP-c).
 *
 * <p>The capability is also bound over MCP/SSE (for future LLM-driven tool selection), but this
 * deterministic call — the agent already knows it wants a caption with a specific instruction —
 * goes over the HTTP passthrough, which (unlike MCP/SSE) is MockWebServer-testable. Same shape as
 * {@link MoneyProImportClient}. Generous timeout: a vision round-trip can be slow.
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
