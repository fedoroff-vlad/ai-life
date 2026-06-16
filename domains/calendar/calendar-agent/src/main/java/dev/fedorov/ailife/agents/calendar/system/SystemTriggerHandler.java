package dev.fedorov.ailife.agents.calendar.system;

import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import reactor.core.publisher.Mono;

/**
 * Handler for a single non-LLM trigger kind. The contract:
 * <ul>
 *   <li>{@link #kind()} matches against the path variable in TriggerController.</li>
 *   <li>{@link #handle(AgentWakeRequest)} returns a {@code Mono<Void>} — completion
 *       (not value) signals success and TriggerController turns it into 202.</li>
 * </ul>
 * Errors are surfaced as Mono errors; the controller logs + still returns 202 so
 * scheduler-service does not retry forever on a malformed payload.
 */
public interface SystemTriggerHandler {

    String kind();

    Mono<Void> handle(AgentWakeRequest req);
}
