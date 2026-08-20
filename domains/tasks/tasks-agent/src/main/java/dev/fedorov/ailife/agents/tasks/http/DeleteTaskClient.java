package dev.fedorov.ailife.agents.tasks.http;

import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Deletes a task via mcp-tasks' non-MCP REST passthrough ({@code DELETE /internal/task/{id}}) and returns
 * the deleted row. This is the deterministic reversal behind the "отмени последнее" undo primitive
 * (road-test #486, Track H): tasks-agent's {@code /actions/undo} calls it to reverse a just-captured task.
 * 5s timeout; an unknown id (404) or any error propagates so the caller ({@code ActionController.undo}) can
 * surface an honest "не нашёл задачу для отмены" instead of pretending it undid something. Mirrors
 * {@link AddTaskClient}.
 */
@Component
public class DeleteTaskClient {

    private final WebClient http;

    public DeleteTaskClient(@Qualifier("mcpTasksWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<TaskItemDto> delete(UUID id) {
        return http.delete()
                .uri("/internal/task/{id}", id)
                .retrieve()
                .bodyToMono(TaskItemDto.class)
                .timeout(Duration.ofSeconds(5));
    }
}
