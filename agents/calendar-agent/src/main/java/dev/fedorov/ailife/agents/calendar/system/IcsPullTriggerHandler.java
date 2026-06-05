package dev.fedorov.ailife.agents.calendar.system;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.agents.calendar.http.IcsImportClient;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Handles the {@code ics.pull} trigger fired by scheduler-service. The payload
 * must carry {@code subscriptionId}; we forward to mcp-ics-import's
 * {@code POST /internal/pull/{id}}.
 */
@Component
public class IcsPullTriggerHandler implements SystemTriggerHandler {

    private static final Logger log = LoggerFactory.getLogger(IcsPullTriggerHandler.class);

    private final IcsImportClient ics;

    public IcsPullTriggerHandler(IcsImportClient ics) {
        this.ics = ics;
    }

    @Override
    public String kind() {
        return "ics.pull";
    }

    @Override
    public Mono<Void> handle(AgentWakeRequest req) {
        UUID subscriptionId = extractSubscriptionId(req.payload());
        if (subscriptionId == null) {
            log.warn("ics.pull wake without subscriptionId (schedule={})", req.scheduleId());
            return Mono.empty();
        }
        return ics.pull(subscriptionId)
                .doOnNext(r -> log.info(
                        "ics.pull subscription={} upserted={} removed={} error={}",
                        subscriptionId, r.eventsUpserted(), r.eventsRemoved(), r.error()))
                .doOnError(e -> log.warn(
                        "ics.pull forward failed for subscription={}: {}",
                        subscriptionId, e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private static UUID extractSubscriptionId(JsonNode payload) {
        if (payload == null) return null;
        JsonNode node = payload.get("subscriptionId");
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
