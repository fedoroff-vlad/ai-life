package dev.fedorov.ailife.agents.calendar.http;

import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.calendar.CreateEventInput;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Drives mcp-caldav's non-MCP {@code POST /internal/event} (write) and
 * {@code GET /internal/events} (read). Used by the {@code create_event} inter-agent action —
 * straight HTTP, no LLM in the loop (the caller already has the concrete event fields).
 */
@Component
public class CaldavEventClient {

    private final WebClient http;

    public CaldavEventClient(WebClient mcpCaldavWebClient) {
        this.http = mcpCaldavWebClient;
    }

    public Mono<CalendarEventDto> createEvent(CreateEventInput input) {
        return http.post()
                .uri("/internal/event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(CalendarEventDto.class);
    }

    /**
     * Events in {@code [from, to)} for one household (start within the window, ascending) — the
     * deterministic {@code GET /internal/events} read. Used by the {@code create_event} double-booking
     * sanity spot-check (#485).
     */
    public Mono<List<CalendarEventDto>> eventsInWindow(UUID householdId, Instant from, Instant to) {
        return http.get()
                .uri(b -> b.path("/internal/events")
                        .queryParam("householdId", householdId)
                        .queryParam("from", from.toString())
                        .queryParam("to", to.toString())
                        .build())
                .retrieve()
                .bodyToFlux(CalendarEventDto.class)
                .collectList();
    }
}
