package dev.fedorov.ailife.mcp.nutrition.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.bus.EventBusMessage;
import dev.fedorov.ailife.contracts.basket.BasketCapturedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Bus consumer for the grocery fan-out (IA-b). Drains {@code basket.captured} events (finance is the
 * producer, IA-a) and forwards each to nutritionist-agent's {@code POST /internal/basket-event},
 * where the breakdown reasoning runs. Agents stay DB-less, so the bus listener lives here in the
 * domain-MCP (owner-chosen 2026-06-23) rather than in the agent; this handler is the thin bridge.
 *
 * <p>Retry policy (encoded by throw vs return, like notifier's {@code NotifyEventHandler}):
 * <ul>
 *   <li>foreign topic / unparsable payload → return (permanent: never retry a bad row);</li>
 *   <li>agent accepted (2xx) → return (done);</li>
 *   <li>agent transient failure (5xx / timeout / connection refused) → throw, so the outbox row
 *       stays PENDING and the next poll redelivers it (at-least-once).</li>
 * </ul>
 */
@Component
public class BasketCapturedHandler {

    private static final Logger log = LoggerFactory.getLogger(BasketCapturedHandler.class);
    private static final Duration FORWARD_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient http;
    private final ObjectMapper json;

    public BasketCapturedHandler(@Qualifier("nutritionistAgentWebClient") WebClient http,
                                 ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    public void onEvent(EventBusMessage message) {
        if (!BasketCapturedEvent.TOPIC.equals(message.topic())) {
            return; // not ours — ignore (the listener still marks the row PUBLISHED)
        }

        BasketCapturedEvent event;
        try {
            event = json.readValue(message.payload(), BasketCapturedEvent.class);
        } catch (Exception e) {
            log.warn("dropping malformed {} event {}: {}",
                    BasketCapturedEvent.TOPIC, message.id(), e.getMessage());
            return; // permanent: a bad payload will never parse
        }
        if (event.householdId() == null) {
            log.warn("dropping {} event {} with no householdId", BasketCapturedEvent.TOPIC, message.id());
            return; // permanent
        }

        // Blocking is fine: the listener runs the handler on its own drain thread. A 2xx completes;
        // a 4xx/5xx/timeout/connection error throws → the row stays PENDING and is retried.
        http.post()
                .uri("/internal/basket-event")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(event)
                .retrieve()
                .toBodilessEntity()
                .timeout(FORWARD_TIMEOUT)
                .block();
        log.debug("forwarded {} for household {} to nutritionist-agent",
                BasketCapturedEvent.TOPIC, event.householdId());
    }
}
