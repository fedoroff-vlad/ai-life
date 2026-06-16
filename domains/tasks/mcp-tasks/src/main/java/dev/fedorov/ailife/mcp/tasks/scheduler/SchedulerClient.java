package dev.fedorov.ailife.mcp.tasks.scheduler;

import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.mcp.tasks.config.McpTasksProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin client to scheduler-service for the per-household {@code weekly.review}
 * cron. Mirrors mcp-finance's {@code SchedulerClient}. All methods are
 * synchronous (block) because they're called from inside the MCP-tool execution
 * path, which is already non-reactive.
 *
 * <p>Unlike budgets — which colocate the {@code schedule_id} on a {@code fin_budget}
 * row — there is no natural tasks table to hold the weekly-review schedule id: the
 * review is exactly one-per-household, perfectly identifiable by
 * {@code (householdId, kind=weekly.review)}. So enable/disable reconcile against
 * scheduler-service's list endpoint instead of a local column.
 */
@Component
public class SchedulerClient {

    private static final Logger log = LoggerFactory.getLogger(SchedulerClient.class);

    private final WebClient http;
    private final McpTasksProperties props;

    public SchedulerClient(WebClient schedulerWebClient, McpTasksProperties props) {
        this.http = schedulerWebClient;
        this.props = props;
    }

    /**
     * Find this household's existing {@code weekly.review} schedule, if any.
     * Errors propagate — the caller cannot safely reconcile (and risks creating
     * a duplicate cron) if it can't read the current state.
     */
    public Optional<ScheduleDto> findWeeklyReview(UUID householdId) {
        ScheduleDto[] all = http.get()
                .uri(uri -> uri.path("/v1/schedules").queryParam("householdId", householdId).build())
                .retrieve()
                .bodyToMono(ScheduleDto[].class)
                .block();
        if (all == null) return Optional.empty();
        String kind = props.getReview().getTriggerKind();
        return Arrays.stream(all).filter(s -> kind.equals(s.kind())).findFirst();
    }

    /**
     * Register the recurring weekly-review schedule. The wake carries no payload:
     * {@code householdId} travels on the schedule row and reaches tasks-agent via
     * {@code AgentWakeRequest.householdId()}, where {@code TriggerController} enriches
     * it from mcp-tasks' {@code /internal/review} aggregate. Errors propagate — there
     * is no local row to fall back to, so a scheduler outage surfaces to the caller.
     */
    public ScheduleDto register(UUID householdId, String cron) {
        CreateScheduleRequest req = new CreateScheduleRequest(
                householdId,
                props.getReview().getOwnerAgent(),
                props.getReview().getTriggerKind(),
                cron,
                null,
                null);
        ScheduleDto created = http.post()
                .uri("/v1/schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(ScheduleDto.class)
                .block();
        if (created == null) {
            throw new IllegalStateException("scheduler-service returned an empty body for weekly.review");
        }
        log.info("registered weekly.review schedule {} for household {} (cron={})",
                created.id(), householdId, cron);
        return created;
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
            log.info("deleted weekly.review schedule {}", scheduleId);
        } catch (WebClientResponseException.NotFound ignored) {
            // already gone — fine
        } catch (RuntimeException e) {
            log.warn("scheduler delete failed for {}: {}", scheduleId, e.toString());
        }
    }
}
