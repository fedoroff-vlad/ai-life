package dev.fedorov.ailife.agents.tasks.http;

import dev.fedorov.ailife.contracts.tasks.ClarifyTaskInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Applies a GTD clarification via mcp-tasks' non-MCP REST passthrough
 * ({@code POST /internal/clarify}). Used by the {@code inbox-clarify} confirm flow to apply each
 * proposed clarification once the user confirms. 2s timeout; errors propagate so the caller can
 * count failures and report honestly.
 */
@Component
public class ClarifyClient {

    private final WebClient http;

    public ClarifyClient(@Qualifier("mcpTasksWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<TaskItemDto> clarify(ClarifyTaskInput input) {
        return http.post()
                .uri("/internal/clarify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(TaskItemDto.class)
                .timeout(Duration.ofSeconds(2));
    }
}
