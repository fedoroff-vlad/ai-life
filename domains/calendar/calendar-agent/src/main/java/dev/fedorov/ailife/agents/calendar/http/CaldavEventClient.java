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
     * Delete an event by its internal id via mcp-caldav's {@code DELETE /internal/event/{id}} — the undo
     * reversal (#486/Track H.2, HC-2): calendar-agent's {@code /actions/undo} calls it to cancel a
     * just-created event. A {@code 204} completes empty; an unknown id (404) or any error propagates so
     * {@code ActionController.undo} can surface an honest "не нашёл событие для отмены".
     */
    public Mono<Void> deleteEvent(UUID id) {
        return http.delete()
                .uri("/internal/event/{id}", id)
                .retrieve()
                .bodyToMono(Void.class);
    }

    /**
     * Events in {@code [from, to)} for one household (start within the window, ascending) — the
     * deterministic {@code GET /internal/events} read. Used by the {@code create_event} double-booking
     * sanity spot-check (#485).
     */
    public Mono<List<CalendarEventDto>> eventsInWindow(UUID householdId, Instant from, Instant to) {
        return eventsInWindow(List.of(householdId), from, to);
    }

    /**
     * Events in {@code [from, to)} across a set of households (the caller's personal ∪ shared set —
     * {@code householdId} is repeatable on {@code /internal/events}, ADR-0001 slice 5), ascending by
     * start. Backs the {@code event-cancel} chat flow's target resolution (#486/Track H.2, HC-3): read
     * the owner's upcoming events, then let the LLM pick which one they mean.
     */
    public Mono<List<CalendarEventDto>> eventsInWindow(List<UUID> householdIds, Instant from, Instant to) {
        return http.get()
                .uri(b -> {
                    b.path("/internal/events")
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString());
                    householdIds.forEach(h -> b.queryParam("householdId", h));
                    return b.build();
                })
                .retrieve()
                .bodyToFlux(CalendarEventDto.class)
                .collectList();
    }
}
