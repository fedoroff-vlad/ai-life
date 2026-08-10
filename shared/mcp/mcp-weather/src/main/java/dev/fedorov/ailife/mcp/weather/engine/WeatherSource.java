package dev.fedorov.ailife.mcp.weather.engine;

import dev.fedorov.ailife.contracts.weather.ClimateNormals;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
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

    /**
     * Resolve a stated place/city name to a {@link GeoLocation} (coordinates + canonical name +
     * timezone). {@code language} is an optional ISO-639 hint. Returns a {@link GeoLocation} with null
     * fields when the source finds no match (not an error).
     */
    Mono<GeoLocation> geocode(String name, String language);

    /**
     * Monthly climate normals (average daily-mean temperature + average monthly precipitation total)
     * for a location over a fixed recent reference period. {@code month} is an optional 1–12 filter:
     * when set only that month is returned, otherwise all twelve (ordered Jan→Dec). Unlike
     * {@link #forecast}/{@link #geocode}, a transport failure does <b>not</b> propagate — it maps to
     * an empty {@link ClimateNormals} (empty {@code months}) so callers degrade rather than see a 500.
     */
    Mono<ClimateNormals> climate(double latitude, double longitude, Integer month);
}
