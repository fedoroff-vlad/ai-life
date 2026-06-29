package dev.fedorov.ailife.mcp.weather.engine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.weather.Weather;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Default {@link WeatherSource}: reads today's forecast from <b>Open-Meteo</b>'s JSON endpoint
 * ({@code GET /v1/forecast?latitude=&longitude=&daily=...&forecast_days=1&timezone=auto}) and maps
 * the first {@code daily} row to a {@link Weather}. Free, no API key, no quota. Selected by
 * {@code weather.source=open-meteo} (the default).
 *
 * <p>Missing fields map to {@code null} (a {@link Weather} with a null field means "no data", not an
 * error). A genuine transport failure propagates on the {@link Mono} (the caller's gather soft-fails).
 */
@Component
@ConditionalOnProperty(name = "weather.source", havingValue = "open-meteo", matchIfMissing = true)
public class OpenMeteoWeatherSource implements WeatherSource {

    /** WMO weather-interpretation codes → human label (the subset Open-Meteo emits). */
    private static final Map<Integer, String> WMO = Map.ofEntries(
            Map.entry(0, "Clear sky"),
            Map.entry(1, "Mainly clear"),
            Map.entry(2, "Partly cloudy"),
            Map.entry(3, "Overcast"),
            Map.entry(45, "Fog"),
            Map.entry(48, "Depositing rime fog"),
            Map.entry(51, "Light drizzle"),
            Map.entry(53, "Moderate drizzle"),
            Map.entry(55, "Dense drizzle"),
            Map.entry(56, "Light freezing drizzle"),
            Map.entry(57, "Dense freezing drizzle"),
            Map.entry(61, "Slight rain"),
            Map.entry(63, "Moderate rain"),
            Map.entry(65, "Heavy rain"),
            Map.entry(66, "Light freezing rain"),
            Map.entry(67, "Heavy freezing rain"),
            Map.entry(71, "Slight snowfall"),
            Map.entry(73, "Moderate snowfall"),
            Map.entry(75, "Heavy snowfall"),
            Map.entry(77, "Snow grains"),
            Map.entry(80, "Slight rain showers"),
            Map.entry(81, "Moderate rain showers"),
            Map.entry(82, "Violent rain showers"),
            Map.entry(85, "Slight snow showers"),
            Map.entry(86, "Heavy snow showers"),
            Map.entry(95, "Thunderstorm"),
            Map.entry(96, "Thunderstorm with slight hail"),
            Map.entry(99, "Thunderstorm with heavy hail"));

    private final WebClient http;

    public OpenMeteoWeatherSource(@Qualifier("openMeteoWebClient") WebClient http) {
        this.http = http;
    }

    @Override
    public Mono<Weather> forecast(double latitude, double longitude) {
        return http.get()
                .uri(uri -> uri.path("/v1/forecast")
                        .queryParam("latitude", latitude)
                        .queryParam("longitude", longitude)
                        .queryParam("daily",
                                "temperature_2m_max,temperature_2m_min,weather_code,"
                                        + "precipitation_probability_max,wind_speed_10m_max")
                        .queryParam("timezone", "auto")
                        .queryParam("forecast_days", 1)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(json -> parse(latitude, longitude, json));
    }

    /** Read index 0 of each {@code daily} array (today). Absent arrays/values → null fields. */
    private static Weather parse(double latitude, double longitude, JsonNode root) {
        JsonNode daily = root == null ? null : root.path("daily");
        if (daily == null || daily.isMissingNode() || daily.isEmpty()) {
            return new Weather(latitude, longitude, null, null, null, null, null, null, null);
        }
        String date = text(daily.path("time"));
        Double tempMax = dbl(daily.path("temperature_2m_max"));
        Double tempMin = dbl(daily.path("temperature_2m_min"));
        Integer code = intg(daily.path("weather_code"));
        Integer precip = intg(daily.path("precipitation_probability_max"));
        Double wind = dbl(daily.path("wind_speed_10m_max"));
        String summary = code == null ? null : WMO.get(code);
        return new Weather(latitude, longitude, date, tempMax, tempMin, precip, wind, code, summary);
    }

    private static JsonNode first(JsonNode array) {
        return array != null && array.isArray() && !array.isEmpty() ? array.get(0) : null;
    }

    private static String text(JsonNode array) {
        JsonNode v = first(array);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Double dbl(JsonNode array) {
        JsonNode v = first(array);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    private static Integer intg(JsonNode array) {
        JsonNode v = first(array);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asInt();
    }
}
