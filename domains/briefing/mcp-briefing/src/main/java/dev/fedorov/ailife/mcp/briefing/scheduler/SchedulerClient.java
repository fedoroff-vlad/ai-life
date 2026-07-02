package dev.fedorov.ailife.mcp.briefing.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.mcp.briefing.config.McpBriefingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.UUID;

/**
 * Thin client to scheduler-service for the auto-registered per-profile
 * {@code briefing.digest} morning wake (BR-f2). Mirrors mcp-finance's {@code
 * SchedulerClient}. Both methods block because they run inside the MCP-tool
 * execution path, which is already non-reactive.
 *
 * <p>Soft-fail policy: a flaky scheduler must not break {@code setBriefingProfile}.
 * A register failure returns {@code null} so the profile row is still saved
 * (without a {@code schedule_id}) and a future reconciliation pass can wire the
 * cron later. A delete failure is logged at WARN and otherwise swallowed —
 * orphaned schedule rows are an annoyance, not a data-integrity problem.
 */
@Component
public class SchedulerClient {

    private static final Logger log = LoggerFactory.getLogger(SchedulerClient.class);

    private final WebClient http;
    private final McpBriefingProperties props;
    private final ObjectMapper json;

    public SchedulerClient(WebClient schedulerWebClient,
                           McpBriefingProperties props,
                           ObjectMapper json) {
        this.http = schedulerWebClient;
        this.props = props;
        this.json = json;
    }

    /**
     * Register a recurring schedule that wakes the briefing agent with
     * {@code kind = briefing.digest} and a payload carrying the {@code ownerId}
     * the digest is for (null owner = household-default → the trigger fans out
     * across the household). briefing-agent's {@code TriggerController} reuses
     * the digest flow with that owner.
     *
     * @return the new schedule id, or {@code null} if scheduler-service was
     *         unreachable — the profile row is still saved.
     */
    public UUID register(UUID householdId, UUID ownerId, String cron) {
        ObjectNode payload = json.createObjectNode();
        if (ownerId != null) {
            payload.put("ownerId", ownerId.toString());
        }

        CreateScheduleRequest req = new CreateScheduleRequest(
                householdId,
                props.getSchedule().getOwnerAgent(),
                props.getSchedule().getTriggerKind(),
                cron,
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
                log.warn("scheduler returned empty body for briefing profile owner {} (cron={})",
                        ownerId, cron);
                return null;
            }
            log.info("registered briefing schedule {} for owner {} (cron={})",
                    created.id(), ownerId, cron);
            return created.id();
        } catch (RuntimeException e) {
            log.warn("scheduler register failed for briefing owner {}: {}", ownerId, e.toString());
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
            log.info("deleted briefing schedule {}", scheduleId);
        } catch (WebClientResponseException.NotFound ignored) {
            // already gone — fine
        } catch (RuntimeException e) {
            log.warn("scheduler delete failed for {}: {}", scheduleId, e.toString());
        }
    }
}
