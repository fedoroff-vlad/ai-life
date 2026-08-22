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
 * Reads task lists from mcp-tasks' non-MCP REST passthrough
 * ({@code GET /internal/tasks?householdId=&status=&limit=}). Two consumers: the
 * {@code next-action-suggester} intent skill fetches open next-actions ({@link #fetchNextActions});
 * the {@code task-delete} flow fetches all open tasks as delete candidates ({@link #fetchTasks} with a
 * null status, #486/Track H.2). Errors (5xx / network / 2s timeout) propagate so the caller degrades to a
 * friendly message.
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
        return fetchTasks(householdId, "next", limit);
    }

    /** Tasks in a household, optionally filtered by {@code status} (null → every status), capped at {@code limit}. */
    public Mono<List<TaskItemDto>> fetchTasks(UUID householdId, String status, int limit) {
        return http.get()
                .uri(uri -> {
                    uri.path("/internal/tasks")
                            .queryParam("householdId", householdId)
                            .queryParam("limit", limit);
                    if (status != null) {
                        uri.queryParam("status", status);
                    }
                    return uri.build();
                })
                .retrieve()
                .bodyToMono(LIST)
                .timeout(Duration.ofSeconds(2));
    }
}
