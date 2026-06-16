package dev.fedorov.ailife.agents.tasks.http;

import dev.fedorov.ailife.contracts.tasks.LinkTaskToEventInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Records a calendar event UID on a task via mcp-tasks' {@code POST /internal/link-event}
 * passthrough. Used by the task-to-event flow after calendar-agent created the event.
 */
@Component
public class LinkEventClient {

    private final WebClient http;

    public LinkEventClient(@Qualifier("mcpTasksWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<TaskItemDto> link(LinkTaskToEventInput input) {
        return http.post()
                .uri("/internal/link-event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(TaskItemDto.class)
                .timeout(Duration.ofSeconds(2));
    }
}
