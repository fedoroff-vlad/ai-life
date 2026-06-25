package dev.fedorov.ailife.agents.creator.http;

import dev.fedorov.ailife.contracts.creator.ContentPieceDto;
import dev.fedorov.ailife.contracts.creator.SaveContentPieceInput;
import dev.fedorov.ailife.contracts.creator.SaveTrendInput;
import dev.fedorov.ailife.contracts.creator.TrendDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Writes what the content-strategist flow (CR-e) gathered and generated into the {@code mcp-creator}
 * cache over its {@code /internal/*} passthroughs (the MCP/SSE binding stays for future LLM-driven
 * selection but isn't MockWebServer-testable). The gathered {@code TrendHit} corpus goes to
 * {@code POST /internal/trends} in one batch (the cache dedups per (owner, url)); the synthesized plan
 * goes to {@code POST /internal/content-piece} as a draft. Persisting is best-effort — the flow has
 * already replied with the deliverable link, so a cache miss never fails the user reply.
 */
@Component
public class CreatorCacheClient {

    private static final ParameterizedTypeReference<List<TrendDto>> TRENDS = new ParameterizedTypeReference<>() {};
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient http;

    public CreatorCacheClient(@Qualifier("mcpCreatorWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<List<TrendDto>> saveTrends(List<SaveTrendInput> inputs) {
        return http.post()
                .uri("/internal/trends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(inputs)
                .retrieve()
                .bodyToMono(TRENDS)
                .timeout(TIMEOUT);
    }

    public Mono<ContentPieceDto> saveContentPiece(SaveContentPieceInput input) {
        return http.post()
                .uri("/internal/content-piece")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(ContentPieceDto.class)
                .timeout(TIMEOUT);
    }
}
