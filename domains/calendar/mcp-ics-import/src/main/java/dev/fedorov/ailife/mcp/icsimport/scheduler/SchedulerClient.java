package dev.fedorov.ailife.mcp.icsimport.scheduler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.mcp.icsimport.config.McpIcsImportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.UUID;

/**
 * Thin client to scheduler-service for the auto-registered hourly pull cron.
 * Both methods are synchronous (block) because they're called from inside the
 * MCP-tool execution path — that is already non-reactive.
 */
@Component
public class SchedulerClient {

    private static final Logger log = LoggerFactory.getLogger(SchedulerClient.class);

    private final WebClient http;
    private final McpIcsImportProperties props;
    private final ObjectMapper json;

    public SchedulerClient(WebClient schedulerWebClient,
                           McpIcsImportProperties props,
                           ObjectMapper json) {
        this.http = schedulerWebClient;
        this.props = props;
        this.json = json;
    }

    /**
     * Register a recurring schedule that will wake the configured owner agent
     * with {@code kind = ics.pull} and a payload carrying the subscription id.
     * Returns the new schedule id, or {@code null} if the scheduler is unreachable
     * (we don't want a flaky scheduler to break add_subscription — the cron can be
     * created later by a reconciliation pass).
     */
    public UUID register(UUID householdId, UUID subscriptionId) {
        ObjectNode payload = json.createObjectNode();
        payload.put("subscriptionId", subscriptionId.toString());

        CreateScheduleRequest req = new CreateScheduleRequest(
                householdId,
                props.getPullOwnerAgent(),
                props.getPullTriggerKind(),
                props.getPullCron(),
                null,
                (JsonNode) payload);

        try {
            ScheduleDto created = http.post()
                    .uri("/v1/schedules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(ScheduleDto.class)
                    .block();
            if (created == null) {
                log.warn("scheduler returned empty body for subscription {}", subscriptionId);
                return null;
            }
            log.info("registered schedule {} for subscription {} (cron={})",
                    created.id(), subscriptionId, props.getPullCron());
            return created.id();
        } catch (RuntimeException e) {
            log.warn("scheduler register failed for subscription {}: {}", subscriptionId, e.toString());
            return null;
        }
    }

    /** Best-effort delete. 404 is treated as success — the row is gone either way. */
    public void delete(UUID scheduleId) {
        if (scheduleId == null) return;
        try {
            http.delete()
                    .uri("/v1/schedules/{id}", scheduleId)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("deleted schedule {}", scheduleId);
        } catch (WebClientResponseException.NotFound ignored) {
            // already gone — fine
        } catch (RuntimeException e) {
            log.warn("scheduler delete failed for {}: {}", scheduleId, e.toString());
        }
    }
}
