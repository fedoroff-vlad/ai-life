package dev.fedorov.ailife.mcp.fooddata.engine;

import dev.fedorov.ailife.contracts.food.FoodFacts;
import reactor.core.publisher.Mono;

/**
 * Pluggable food-data backend. The default is {@link OpenFoodFactsDataSource} (free, no key); a
 * sibling source (USDA / a keyed provider) can replace it later via {@code fooddata.source} with no
 * caller change. Mirrors {@code mcp-market-data}'s {@code MarketDataSource}. Read-only — there is no
 * write method (the capability is reference data only). When the source has no product for the query
 * it returns a {@link FoodFacts} with a null {@code name} (not an error); a genuine transport failure
 * propagates on the {@link Mono}.
 */
public interface FoodDataSource {

    Mono<FoodFacts> lookup(String query);
}
