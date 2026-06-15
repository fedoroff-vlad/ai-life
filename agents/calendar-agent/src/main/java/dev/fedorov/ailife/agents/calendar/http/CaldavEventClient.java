package dev.fedorov.ailife.agents.calendar.http;

import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.calendar.CreateEventInput;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Drives mcp-caldav's non-MCP {@code POST /internal/event}. Used by the
 * {@code create_event} inter-agent action — straight HTTP, no LLM in the loop
 * (the caller already has the concrete event fields).
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
}
