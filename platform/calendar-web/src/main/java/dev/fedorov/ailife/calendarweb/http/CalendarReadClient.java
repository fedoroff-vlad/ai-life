package dev.fedorov.ailife.calendarweb.http;

import dev.fedorov.ailife.calendarweb.config.CalendarWebProperties;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads events from mcp-caldav's deterministic read passthrough ({@code GET /internal/events}) — the
 * official inter-service surface (doctrine #201). calendar-web owns no calendar schema; all reads go
 * through mcp-caldav.
 */
@Component
public class CalendarReadClient {

    private static final ParameterizedTypeReference<List<CalendarEventDto>> EVENT_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;

    public CalendarReadClient(WebClient.Builder builder, CalendarWebProperties props) {
        this.http = builder.baseUrl(props.getMcpCaldavUrl()).build();
    }

    /** Events whose start is within {@code [from, to)} for the household, ordered by start ascending. */
    public Mono<List<CalendarEventDto>> events(UUID householdId, Instant from, Instant to) {
        return http.get()
                .uri(b -> b.path("/internal/events")
                        .queryParam("householdId", householdId)
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString())
                        .build())
                .retrieve()
                .bodyToMono(EVENT_LIST);
    }
}
