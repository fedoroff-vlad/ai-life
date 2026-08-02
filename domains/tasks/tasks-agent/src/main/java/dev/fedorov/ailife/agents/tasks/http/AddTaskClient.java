package dev.fedorov.ailife.agents.tasks.http;

import dev.fedorov.ailife.contracts.tasks.AddTaskInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Captures a task via mcp-tasks' non-MCP REST passthrough ({@code POST /internal/task}). Used by the
 * {@code task-capture} flow ({@code TaskCapturer}, ADR-0002 slice 5) to persist a task under the household
 * the shared {@code SharingResolver} already routed to (personal vs shared) — the deterministic write path
 * an LLM-driven {@code add_task} tool call cannot take, since the classifier never sees the household id.
 * 5s timeout; errors propagate so the caller can degrade to a friendly message. Mirrors {@link ClarifyClient}.
 */
@Component
public class AddTaskClient {

    private final WebClient http;

    public AddTaskClient(@Qualifier("mcpTasksWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<TaskItemDto> add(AddTaskInput input) {
        return http.post()
                .uri("/internal/task")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(TaskItemDto.class)
                .timeout(Duration.ofSeconds(5));
    }
}
