package dev.fedorov.ailife.contracts.market;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for the {@code mcp-market-data} {@code POST /internal/quote} passthrough (mirrors
 * the {@code quote} tool args). {@code symbol} is the source-native symbol (e.g. Stooq's
 * {@code aapl.us} / {@code ^spx} / {@code xauusd} / {@code btcusd}) — mapping a human ticker to it
 * is the calling agent's job. The passthrough is the deterministic, MockWebServer-testable path an
 * agent calls (MCP/SSE can't be MockWebServer'd).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteInput(
        String symbol) {
}
