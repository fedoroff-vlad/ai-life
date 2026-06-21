package dev.fedorov.ailife.agents.stylist.http;

import dev.fedorov.ailife.contracts.wardrobe.SetStyleProfileInput;
import dev.fedorov.ailife.contracts.wardrobe.StyleProfileDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the {@code mcp-wardrobe} domain-MCP's {@code POST /internal/profile} passthrough (ST-d) to
 * upsert the person's style profile. The "analyse me" flow has computed a concrete
 * {@link SetStyleProfileInput} from the self-photo + typed params, so it writes deterministically
 * over HTTP. Same shape as {@link WardrobeClient}.
 */
@Component
public class StyleProfileClient {

    private final WebClient http;

    public StyleProfileClient(@Qualifier("mcpWardrobeWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<StyleProfileDto> set(SetStyleProfileInput input) {
        return http.post()
                .uri("/internal/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(StyleProfileDto.class)
                .timeout(Duration.ofSeconds(10));
    }
}
