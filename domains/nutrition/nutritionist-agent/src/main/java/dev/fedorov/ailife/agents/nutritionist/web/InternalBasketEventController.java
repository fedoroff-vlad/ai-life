package dev.fedorov.ailife.agents.nutritionist.web;

import dev.fedorov.ailife.agents.nutritionist.basket.BasketBreakdown;
import dev.fedorov.ailife.contracts.basket.BasketCapturedEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Inbound entry for the grocery-receipt fan-out (IA-b). mcp-nutrition consumes the
 * {@code basket.captured} bus event (finance is the producer, IA-a) and forwards it here over HTTP —
 * agents stay DB-less, so the bus listener lives in the domain-MCP and the breakdown reasoning lives
 * in the agent. This runs {@link BasketBreakdown#breakdownFromEvent}: one LLM breakdown over the
 * line items finance already extracted → save the basket → render the verdict board → notify the
 * household. Best-effort (the flow soft-fails internally); returns 202 once accepted so the consumer
 * marks the bus row handled (a transport failure — agent down — surfaces to the consumer for retry).
 */
@RestController
public class InternalBasketEventController {

    private final BasketBreakdown basketBreakdown;

    public InternalBasketEventController(BasketBreakdown basketBreakdown) {
        this.basketBreakdown = basketBreakdown;
    }

    @PostMapping("/internal/basket-event")
    public Mono<ResponseEntity<Void>> basketEvent(@RequestBody BasketCapturedEvent event) {
        return basketBreakdown.breakdownFromEvent(event)
                .thenReturn(ResponseEntity.accepted().<Void>build());
    }
}
