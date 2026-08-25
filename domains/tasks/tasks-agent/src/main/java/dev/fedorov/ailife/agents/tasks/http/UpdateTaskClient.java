package dev.fedorov.ailife.agents.tasks.http;

import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.UpdateTaskInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Edits a task via mcp-tasks' non-MCP REST passthrough ({@code PUT /internal/task/{id}}) and returns the
 * updated row — the deterministic partial content edit behind the user-facing {@code task-edit} chat flow
 * (road-test #486, Track H.2). Only the non-null fields on {@code input} change (mcp-tasks patches, missing
 * fields keep their value), so a rename/reschedule sends just the new title/due. 5s timeout; an unknown id
 * (404) or any error propagates so {@code TaskEditor.resume} can be honest that the task is gone. Mirrors
 * {@link DeleteTaskClient} and calendar's {@code CaldavEventClient.updateEvent}.
 */
@Component
public class UpdateTaskClient {

    private final WebClient http;

    public UpdateTaskClient(@Qualifier("mcpTasksWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<TaskItemDto> update(UUID id, UpdateTaskInput input) {
        return http.put()
                .uri("/internal/task/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(TaskItemDto.class)
                .timeout(Duration.ofSeconds(5));
    }
}
