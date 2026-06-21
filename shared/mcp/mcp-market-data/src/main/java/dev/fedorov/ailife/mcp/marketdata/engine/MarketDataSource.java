package dev.fedorov.ailife.mcp.marketdata.engine;

import dev.fedorov.ailife.contracts.market.Quote;
import reactor.core.publisher.Mono;

/**
 * Pluggable quotes backend. The default is {@link StooqMarketDataSource} (free, no key); a sibling
 * source (Yahoo / a keyed provider) can replace it later via {@code marketdata.source} with no
 * caller change. Mirrors {@code mcp-web}'s {@code SearchEngine}. Read-only — there is deliberately
 * no order/trade method (the capability is advisory data only). When the source has no data for a
 * symbol it returns a {@link Quote} with a null {@code price} (not an error); a genuine transport
 * failure propagates on the {@link Mono}.
 */
public interface MarketDataSource {

    Mono<Quote> quote(String symbol);
}
