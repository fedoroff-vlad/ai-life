package dev.fedorov.ailife.agents.docs.http;

import dev.fedorov.ailife.contracts.media.OcrInput;
import dev.fedorov.ailife.contracts.media.OcrResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the shared {@code mcp-media-processing} capability's {@code POST /internal/ocr} passthrough to
 * turn a document photo (media-service object id) into recognised text. The doc-archiver flow already
 * knows it wants OCR, so it hits this deterministic HTTP path rather than an LLM-driven MCP tool call
 * (MCP/SSE can't be MockWebServer'd). Mirrors finance-agent's {@code CaptionClient}. OCR can be slow on
 * a dense scan, so the timeout is generous.
 */
@Component
public class OcrClient {

    private final WebClient http;

    public OcrClient(@Qualifier("mcpMediaProcessingWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<OcrResult> ocr(String mediaId) {
        return http.post()
                .uri("/internal/ocr")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new OcrInput(mediaId))
                .retrieve()
                .bodyToMono(OcrResult.class)
                .timeout(Duration.ofSeconds(60));
    }
}
