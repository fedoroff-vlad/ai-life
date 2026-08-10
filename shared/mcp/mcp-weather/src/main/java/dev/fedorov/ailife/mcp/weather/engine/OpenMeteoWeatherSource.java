package dev.fedorov.ailife.mcp.weather.engine;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.weather.ClimateNormals;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.MonthlyNormal;
import dev.fedorov.ailife.contracts.weather.Weather;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Reference window for the normals: the last {@value} complete calendar years (archive ERA5). */
    private static final int NORMALS_YEARS = 10;

    private final WebClient http;
    private final WebClient geocode;
    private final WebClient climate;

    public OpenMeteoWeatherSource(@Qualifier("openMeteoWebClient") WebClient http,
                                  @Qualifier("geocodeWebClient") WebClient geocode,
                                  @Qualifier("climateWebClient") WebClient climate) {
        this.http = http;
        this.geocode = geocode;
        this.climate = climate;
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

    @Override
    public Mono<GeoLocation> geocode(String name, String language) {
        String q = name == null ? "" : name.trim();
        return geocode.get()
                .uri(uri -> {
                    uri.path("/v1/search")
                            .queryParam("name", q)
                            .queryParam("count", 1)
                            .queryParam("format", "json");
                    if (language != null && !language.isBlank()) {
                        uri.queryParam("language", language.trim());
                    }
                    return uri.build();
                })
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .map(OpenMeteoWeatherSource::parseGeocode);
    }

    @Override
    public Mono<ClimateNormals> climate(double latitude, double longitude, Integer month) {
        int lastYear = LocalDate.now(ZoneOffset.UTC).getYear() - 1;
        LocalDate start = LocalDate.of(lastYear - NORMALS_YEARS + 1, 1, 1);
        LocalDate end = LocalDate.of(lastYear, 12, 31);
        return climate.get()
                .uri(uri -> uri.path("/v1/archive")
                        .queryParam("latitude", latitude)
                        .queryParam("longitude", longitude)
                        .queryParam("start_date", start)
                        .queryParam("end_date", end)
                        .queryParam("daily", "temperature_2m_mean,precipitation_sum")
                        .queryParam("timezone", "auto")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(20))
                .map(json -> parseClimate(latitude, longitude, json, month))
                // A transport/upstream failure degrades to empty normals (not a 500) — TR-a soft-fail.
                .onErrorReturn(new ClimateNormals(latitude, longitude, List.of()));
    }

    /**
     * Aggregate the archive's {@code daily} mean-temperature + precipitation arrays into per-month
     * normals: {@code avgTempC} = mean of daily means in the calendar month across the window;
     * {@code precipMm} = the window's total precipitation for the month divided by the number of
     * years (i.e. the average monthly total). Absent/empty {@code daily} → empty {@code months}.
     */
    private static ClimateNormals parseClimate(double lat, double lon, JsonNode root, Integer month) {
        JsonNode daily = root == null ? null : root.path("daily");
        JsonNode times = daily == null ? null : daily.path("time");
        JsonNode temps = daily == null ? null : daily.path("temperature_2m_mean");
        JsonNode precs = daily == null ? null : daily.path("precipitation_sum");
        if (times == null || !times.isArray() || times.isEmpty()) {
            return new ClimateNormals(lat, lon, List.of());
        }
        double[] tempSum = new double[13];
        int[] tempCount = new int[13];
        double[] precSum = new double[13];
        int[] dayCount = new int[13];
        Set<Integer> years = new HashSet<>();
        for (int i = 0; i < times.size(); i++) {
            JsonNode dNode = times.get(i);
            String d = dNode == null || dNode.isNull() ? null : dNode.asString();
            if (d == null || d.length() < 7) {
                continue;
            }
            int m = Integer.parseInt(d.substring(5, 7));
            if (m < 1 || m > 12) {
                continue;
            }
            years.add(Integer.parseInt(d.substring(0, 4)));
            dayCount[m]++;
            JsonNode t = temps != null && temps.isArray() && i < temps.size() ? temps.get(i) : null;
            if (t != null && t.isNumber()) {
                tempSum[m] += t.asDouble();
                tempCount[m]++;
            }
            JsonNode p = precs != null && precs.isArray() && i < precs.size() ? precs.get(i) : null;
            if (p != null && p.isNumber()) {
                precSum[m] += p.asDouble();
            }
        }
        int nYears = Math.max(1, years.size());
        List<MonthlyNormal> months = new ArrayList<>(12);
        for (int m = 1; m <= 12; m++) {
            Double avgTemp = tempCount[m] > 0 ? round1(tempSum[m] / tempCount[m]) : null;
            Double precip = dayCount[m] > 0 ? round1(precSum[m] / nYears) : null;
            months.add(new MonthlyNormal(m, avgTemp, precip));
        }
        if (month != null && month >= 1 && month <= 12) {
            return new ClimateNormals(lat, lon, List.of(months.get(month - 1)));
        }
        return new ClimateNormals(lat, lon, months);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Read the first {@code results} entry. Absent/empty results → a GeoLocation with null fields. */
    private static GeoLocation parseGeocode(JsonNode root) {
        JsonNode results = root == null ? null : root.path("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return new GeoLocation(null, null, null, null, null);
        }
        JsonNode top = results.get(0);
        return new GeoLocation(
                str(top, "name"),
                str(top, "country"),
                dblField(top, "latitude"),
                dblField(top, "longitude"),
                str(top, "timezone"));
    }

    private static String str(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static Double dblField(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.asDouble();
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
        return v == null || v.isNull() ? null : v.asString();
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
