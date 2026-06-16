package dev.fedorov.ailife.mcp.finance.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.mcp.finance.config.McpFinanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Instant;
import java.util.UUID;

/**
 * Thin client to scheduler-service for the auto-registered budget.alert cron.
 * Mirrors mcp-ics-import's {@code SchedulerClient} (PR13). Both methods are
 * synchronous (block) because they're called from inside the MCP-tool
 * execution path — that path is already non-reactive.
 *
 * <p>Soft-fail policy: a flaky scheduler must not break {@code set_budget}. A
 * register failure returns {@code null} so the budget row is still inserted
 * (without a {@code schedule_id}) and a future reconciliation pass can wire
 * the cron later. A delete failure is logged at WARN and otherwise swallowed
 * — orphaned schedule rows are an annoyance, not a data-integrity problem.
 */
@Component
public class SchedulerClient {

    private static final Logger log = LoggerFactory.getLogger(SchedulerClient.class);

    private final WebClient http;
    private final McpFinanceProperties props;
    private final ObjectMapper json;

    public SchedulerClient(WebClient schedulerWebClient,
                           McpFinanceProperties props,
                           ObjectMapper json) {
        this.http = schedulerWebClient;
        this.props = props;
        this.json = json;
    }

    /**
     * Register a recurring schedule that wakes the configured owner agent with
     * {@code kind = budget.alert} and a payload carrying enough to look up the
     * live snapshot at trigger time: {@code householdId}, {@code categoryId},
     * {@code period}. finance-agent's {@code TriggerController.enrichIfNeeded}
     * is what turns those three keys into the full skill payload.
     *
     * @return the new schedule id, or {@code null} if scheduler-service was
     *         unreachable.
     */
    public UUID register(UUID householdId, UUID categoryId, String period) {
        ObjectNode payload = json.createObjectNode();
        payload.put("categoryId", categoryId.toString());
        payload.put("period", period);

        CreateScheduleRequest req = new CreateScheduleRequest(
                householdId,
                props.getBudget().getOwnerAgent(),
                props.getBudget().getTriggerKind(),
                props.getBudget().getCron(),
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
                log.warn("scheduler returned empty body for budget category {} / period {}",
                        categoryId, period);
                return null;
            }
            log.info("registered budget schedule {} for category {} / period {} (cron={})",
                    created.id(), categoryId, period, props.getBudget().getCron());
            return created.id();
        } catch (RuntimeException e) {
            log.warn("scheduler register failed for category {} / period {}: {}",
                    categoryId, period, e.toString());
            return null;
        }
    }

    /**
     * Register a recurring schedule that wakes the configured recurring owner
     * agent with {@code kind = recurring.due} and a payload carrying just
     * {@code recurringId}. The cron comes from {@code fin_recurring.cron}
     * (per-row, unlike budgets where every alert shares one cron).
     *
     * @return the new schedule id, or {@code null} if scheduler-service was
     *         unreachable — the recurring row is still saved.
     */
    public UUID registerRecurring(UUID householdId, UUID recurringId, String cron) {
        ObjectNode payload = json.createObjectNode();
        payload.put("recurringId", recurringId.toString());

        CreateScheduleRequest req = new CreateScheduleRequest(
                householdId,
                props.getRecurring().getOwnerAgent(),
                props.getRecurring().getTriggerKind(),
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
                log.warn("scheduler returned empty body for recurring {}", recurringId);
                return null;
            }
            log.info("registered recurring schedule {} for recurring {} (cron={})",
                    created.id(), recurringId, cron);
            return created.id();
        } catch (RuntimeException e) {
            log.warn("scheduler register failed for recurring {}: {}", recurringId, e.toString());
            return null;
        }
    }

    /**
     * One-shot wake fired by {@code add_transaction} when a row lands without
     * a category. scheduler-service picks it up on the next tick (default
     * 30s); after firing the row becomes {@code enabled=false} per PR8's
     * one-shot rule. Payload carries just {@code transactionId};
     * finance-agent's enrichment hydrates the rest.
     */
    public UUID registerTransactionUncategorisedOneshot(UUID householdId, UUID transactionId,
                                                        Instant runAt) {
        ObjectNode payload = json.createObjectNode();
        payload.put("transactionId", transactionId.toString());

        CreateScheduleRequest req = new CreateScheduleRequest(
                householdId,
                props.getTransaction().getOwnerAgent(),
                props.getTransaction().getTriggerKind(),
                null,
                runAt,
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
                log.warn("scheduler returned empty body for transaction {}", transactionId);
                return null;
            }
            log.info("registered transaction-uncategorised schedule {} for transaction {} (runAt={})",
                    created.id(), transactionId, runAt);
            return created.id();
        } catch (RuntimeException e) {
            log.warn("scheduler register failed for transaction {}: {}", transactionId, e.toString());
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
            log.info("deleted budget schedule {}", scheduleId);
        } catch (WebClientResponseException.NotFound ignored) {
            // already gone — fine
        } catch (RuntimeException e) {
            log.warn("scheduler delete failed for {}: {}", scheduleId, e.toString());
        }
    }
}
