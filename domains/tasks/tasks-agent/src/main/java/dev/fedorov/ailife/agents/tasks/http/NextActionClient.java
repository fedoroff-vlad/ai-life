package dev.fedorov.ailife.agents.tasks.http;

import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Reads open GTD next-actions from mcp-tasks' non-MCP REST passthrough
 * ({@code GET /internal/tasks?householdId=&status=next}). Used by the {@code next-action-suggester}
 * intent skill to fetch the candidates it ranks. Errors (5xx / network / 2s timeout) propagate so
 * the flow degrades to a friendly message.
 */
@Component
public class NextActionClient {

    private static final ParameterizedTypeReference<List<TaskItemDto>> LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient http;

    public NextActionClient(@Qualifier("mcpTasksWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<List<TaskItemDto>> fetchNextActions(UUID householdId, int limit) {
        return http.get()
                .uri(uri -> uri.path("/internal/tasks")
                        .queryParam("householdId", householdId)
                        .queryParam("status", "next")
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(LIST)
                .timeout(Duration.ofSeconds(2));
    }
}
