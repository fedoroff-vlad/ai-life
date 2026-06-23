package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.basket.BasketCapturedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Fire-and-forget producer for the {@code basket.captured} fan-out (IA-a). When the receipt-parser
 * recognises a grocery basket it posts a {@link BasketCapturedEvent} to mcp-finance's
 * {@code POST /internal/basket-captured} drop-point (IA-a1), which enqueues it onto {@code bus.outbox}
 * for nutritionist-agent to consume (IA-b). finance-agent is DB-less, so the bus write lives in the
 * domain-MCP; the agent just hands it the event.
 *
 * <p>Off the response path (same posture as {@code MemoryClient.observe}): never awaited, never
 * affects the user reply, any failure swallowed. The breakdown fan-out is best-effort — a bus hiccup
 * must not break the receipt's expense-confirmation flow.
 */
@Component
public class BasketCapturedClient {

    private static final Logger log = LoggerFactory.getLogger(BasketCapturedClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final WebClient http;

    public BasketCapturedClient(@Qualifier("mcpFinanceWebClient") WebClient http) {
        this.http = http;
    }

    public void publish(BasketCapturedEvent event) {
        if (event == null || event.householdId() == null) {
            return;
        }
        http.post()
                .uri("/internal/basket-captured")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(event)
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("basket.captured publish failed for household={}: {}",
                            event.householdId(), e.toString());
                    return Mono.empty();
                })
                .subscribe();
    }
}
