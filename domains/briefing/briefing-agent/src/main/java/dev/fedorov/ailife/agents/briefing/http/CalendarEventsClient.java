package dev.fedorov.ailife.agents.briefing.http;

import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads today's events from mcp-caldav's {@code GET /internal/events?householdId=&from=&to=} read
 * passthrough (doctrine #201: {@code /internal/*} is the deterministic inter-service read surface,
 * MCP/SSE is for LLM tool-selection). The digest's agenda gather step calls it for the
 * {@code [todayStart, tomorrowStart)} window in the profile's timezone. Household-scoped for now —
 * per-person agenda filtering tracks the shared calendar-visibility gap (briefing.md §Deferred). A
 * short timeout — it's one Coordinator gather step and must not stall the digest.
 */
@Component
public class CalendarEventsClient {

    private final WebClient http;

    public CalendarEventsClient(@Qualifier("mcpCaldavWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<List<CalendarEventDto>> eventsBetween(UUID householdId, Instant from, Instant to) {
        return http.get()
                .uri(uri -> uri.path("/internal/events")
                        .queryParam("householdId", householdId)
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString())
                        .build())
                .retrieve()
                .bodyToFlux(CalendarEventDto.class)
                .collectList()
                .timeout(Duration.ofSeconds(5));
    }
}
