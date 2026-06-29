package dev.fedorov.ailife.mcp.weather.engine;

import dev.fedorov.ailife.contracts.weather.Weather;
import reactor.core.publisher.Mono;

/**
 * Pluggable forecast backend. The default is {@link OpenMeteoWeatherSource} (free, no key); a keyed
 * provider can replace it later via {@code weather.source} with no caller change. Mirrors
 * {@code mcp-market-data}'s {@code MarketDataSource}. Read-only — there is only a forecast read.
 * When the source has no data for a field it returns a {@link Weather} with that field null (not an
 * error); a genuine transport failure propagates on the {@link Mono} (the caller's gather soft-fails).
 */
public interface WeatherSource {

    Mono<Weather> forecast(double latitude, double longitude);
}
