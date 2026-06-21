package dev.fedorov.ailife.mcp.marketdata.tools;

import dev.fedorov.ailife.contracts.market.Quote;
import dev.fedorov.ailife.mcp.marketdata.engine.MarketDataSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The shared market-data toolbox. {@code quote} (MD-a) reads a latest quote for one symbol from
 * the configured {@link MarketDataSource} (Stooq by default). The capability returns numbers only —
 * no LLM, no decision. Any agent binds this server over MCP/SSE; the deterministic path goes through
 * the {@code /internal/quote} HTTP passthrough. Read-only and **advisory data only** — there is no
 * order/trade tool, by design.
 */
@Component
public class MarketDataMcpTools {

    private final MarketDataSource source;

    public MarketDataMcpTools(MarketDataSource source) {
        this.source = source;
    }

    @Tool(description = """
            Get the latest market quote for one symbol — stocks, indices, ETFs/funds, metals, forex,
            and some crypto. Pass the source-native symbol (e.g. 'aapl.us', '^spx', 'xauusd' for gold,
            'btcusd'); mapping a human ticker to it is your job. Returns the latest price plus the day's
            open/high/low/volume and a timestamp when available; price is null when the source has no
            data for the symbol. This is read-only quote DATA for analysis — it never places orders.
            """)
    public Quote quote(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return new Quote(symbol, null, null, null, null, null, null);
        }
        return source.quote(symbol).block();
    }
}
