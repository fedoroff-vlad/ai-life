package dev.fedorov.ailife.agents.researcher.http;

import dev.fedorov.ailife.contracts.web.FetchUrlInput;
import dev.fedorov.ailife.contracts.web.PageContent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the shared {@code mcp-web} capability's {@code POST /internal/fetch} passthrough to read
 * a page in full. The research flow fetches the top hits in parallel and soft-fails per page, so
 * one slow/broken page never sinks the gather.
 */
@Component
public class PageFetchClient {

    private final WebClient http;

    public PageFetchClient(@Qualifier("mcpWebWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<PageContent> fetch(String url) {
        return http.post()
                .uri("/internal/fetch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new FetchUrlInput(url))
                .retrieve()
                .bodyToMono(PageContent.class)
                .timeout(Duration.ofSeconds(15));
    }
}
