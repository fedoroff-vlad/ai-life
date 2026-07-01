package dev.fedorov.ailife.mcp.weather.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "weather")
public class McpWeatherProperties {

    /** Open-Meteo base URL — where {@code forecast} reads JSON ({@code /v1/forecast?...}). */
    private String openMeteoUrl = "https://api.open-meteo.com";

    /**
     * Open-Meteo Geocoding base URL — where {@code geocode} resolves a city name ({@code /v1/search?...}).
     * A separate host from the forecast API (both free, no key).
     */
    private String geocodeUrl = "https://geocoding-api.open-meteo.com";

    /**
     * Which forecast source to wire: {@code open-meteo} (default, free, no key). Behind the
     * {@code WeatherSource} interface so a keyed provider can replace it later via env with no
     * caller change (mirrors mcp-market-data's source selector).
     */
    private String source = "open-meteo";

    public String getOpenMeteoUrl() { return openMeteoUrl; }
    public void setOpenMeteoUrl(String openMeteoUrl) { this.openMeteoUrl = openMeteoUrl; }
    public String getGeocodeUrl() { return geocodeUrl; }
    public void setGeocodeUrl(String geocodeUrl) { this.geocodeUrl = geocodeUrl; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
