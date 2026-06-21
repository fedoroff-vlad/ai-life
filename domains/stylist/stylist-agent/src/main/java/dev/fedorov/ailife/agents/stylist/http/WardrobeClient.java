package dev.fedorov.ailife.agents.stylist.http;

import dev.fedorov.ailife.contracts.wardrobe.AddItemInput;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the {@code mcp-wardrobe} domain-MCP's {@code POST /internal/item} passthrough (ST-c1) to
 * persist a catalogued garment. The catalogue flow has already extracted a concrete
 * {@link AddItemInput} from a photo caption, so it writes deterministically over HTTP rather than
 * through an LLM-driven MCP tool call (the MCP/SSE binding stays for future tool selection but
 * isn't MockWebServer-testable). Same shape as finance's {@code TransactionClient}.
 */
@Component
public class WardrobeClient {

    private final WebClient http;

    public WardrobeClient(@Qualifier("mcpWardrobeWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<WardrobeItemDto> add(AddItemInput input) {
        return http.post()
                .uri("/internal/item")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(WardrobeItemDto.class)
                .timeout(Duration.ofSeconds(10));
    }
}
