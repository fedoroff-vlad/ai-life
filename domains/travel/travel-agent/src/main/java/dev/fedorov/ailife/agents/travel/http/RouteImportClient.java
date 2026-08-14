package dev.fedorov.ailife.agents.travel.http;

import dev.fedorov.ailife.contracts.travel.ImportRouteInput;
import dev.fedorov.ailife.contracts.travel.RouteDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the {@code mcp-travel} domain-MCP's {@code POST /internal/routes} passthrough (RT-a) to store a
 * parsed route deterministically over HTTP. The route flow (RT-c) has already fetched the file bytes and
 * sniffed its format, so it imports directly rather than through an LLM-driven MCP tool call. Mirrors
 * {@link TripWalletClient}.
 */
@Component
public class RouteImportClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;

    public RouteImportClient(@Qualifier("mcpTravelWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<RouteDto> importRoute(ImportRouteInput input) {
        return http.post().uri("/internal/routes")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(input)
                .retrieve().bodyToMono(RouteDto.class).timeout(TIMEOUT);
    }
}
