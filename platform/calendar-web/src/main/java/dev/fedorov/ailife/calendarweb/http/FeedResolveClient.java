package dev.fedorov.ailife.calendarweb.http;

import dev.fedorov.ailife.calendarweb.config.CalendarWebProperties;
import dev.fedorov.ailife.contracts.calendar.CalendarFeedDto;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Resolves an ICS feed token to its feed via mcp-caldav's {@code GET /internal/feeds/{token}} — the
 * persistent, mint-on-demand source (#195, track B). A 404 (unknown or revoked token) resolves to
 * {@link Optional#empty()} so the caller can fall back to a static env-configured feed. Other errors
 * propagate (so a real outage isn't masked as "unknown token").
 */
@Component
public class FeedResolveClient {

    private final WebClient http;

    public FeedResolveClient(WebClient.Builder builder, CalendarWebProperties props) {
        this.http = builder.baseUrl(props.getMcpCaldavUrl()).build();
    }

    public Mono<Optional<CalendarFeedDto>> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Mono.just(Optional.empty());
        }
        return http.get().uri("/internal/feeds/{token}", token)
                .retrieve()
                .bodyToMono(CalendarFeedDto.class)
                .map(Optional::of)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.just(Optional.empty()));
    }
}
