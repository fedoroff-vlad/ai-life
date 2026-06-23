package dev.fedorov.ailife.mcp.finance.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.bus.OutboxPublisher;
import dev.fedorov.ailife.contracts.basket.BasketCapturedEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Durable async drop-point for the {@code basket.captured} fan-out (IA-a). finance-agent is DB-less,
 * so when its receipt-parser recognises a grocery basket it POSTs the {@link BasketCapturedEvent}
 * here; mcp-finance (which owns a datasource on the shared Postgres) enqueues it onto
 * {@code bus.outbox} via the {@link OutboxPublisher} and returns 202. The nutrition consumer
 * (IA-b) picks it up off the bus and runs its breakdown — so the receipt's vision work is done once
 * and one receipt reaches both finance and nutrition.
 *
 * <p>Mirrors memory-service's {@code POST /v1/observations} drop-point (the established DB-less-producer
 * pattern), scoped to the finance domain's own event.
 */
@RestController
public class InternalBasketCapturedController {

    private final OutboxPublisher outbox;
    private final ObjectMapper json;

    public InternalBasketCapturedController(OutboxPublisher outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @PostMapping("/internal/basket-captured")
    public ResponseEntity<Void> publish(@RequestBody BasketCapturedEvent event) {
        if (event.householdId() == null) {
            return ResponseEntity.badRequest().build();
        }
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().build();
        }
        outbox.publish(BasketCapturedEvent.TOPIC, event.householdId(), payload);
        return ResponseEntity.accepted().build();
    }
}
