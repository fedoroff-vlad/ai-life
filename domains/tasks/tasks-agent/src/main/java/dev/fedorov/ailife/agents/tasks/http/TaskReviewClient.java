package dev.fedorov.ailife.agents.tasks.http;

import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Reads the GTD weekly-review aggregate from mcp-tasks' non-MCP REST passthrough
 * ({@code GET /internal/review}). Used by {@code TriggerController} to enrich a scheduler-driven
 * {@code weekly.review} wake before the LLM call. The endpoint always returns 200 with an
 * aggregate (empty lists when there's nothing to review), so there's no 404 branch — any error
 * (5xx / network / 2s timeout) is propagated so the controller returns 503 and scheduler-service
 * retries on the next tick.
 */
@Component
public class TaskReviewClient {

    private final WebClient http;

    public TaskReviewClient(@Qualifier("mcpTasksWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<WeeklyReviewResult> fetch(UUID householdId) {
        return http.get()
                .uri(uri -> uri.path("/internal/review")
                        .queryParam("householdId", householdId)
                        .build())
                .retrieve()
                .bodyToMono(WeeklyReviewResult.class)
                .timeout(Duration.ofSeconds(2));
    }
}
