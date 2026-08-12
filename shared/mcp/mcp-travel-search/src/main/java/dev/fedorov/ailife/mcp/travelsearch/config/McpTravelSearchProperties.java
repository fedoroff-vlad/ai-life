package dev.fedorov.ailife.mcp.travelsearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the travel-search capability. The {@code token} + {@code marker} are the owner's
 * Travelpayouts affiliate credentials (via env, <b>never committed</b>); when {@code token} is blank the
 * whole capability <b>degrades</b> — every tool returns {@code unconfigured=true} + empty, never a 500,
 * so CI/tests stay green with no key and callers fall back to the planner MVP (ADR-0003 §3). The
 * {@code *ApiUrl}/{@code *SiteUrl} split separates the data endpoints (parsed) from the site hosts (used
 * to build the buy {@code deepLink}, marked with {@code marker}). {@code source} selects the backend
 * behind the swappable {@code TravelSearchSource} so {@code mcp-browser} can replace Travelpayouts later
 * with no caller change.
 */
@ConfigurationProperties(prefix = "travelsearch")
public class McpTravelSearchProperties {

    /** Which search backend to wire: {@code travelpayouts} (default). Swappable later (e.g. browser) via env. */
    private String source = "travelpayouts";

    /** Travelpayouts affiliate token — env only, never committed. Blank → the capability degrades. */
    private String token = "";

    /** Travelpayouts affiliate marker — appended to every buy {@code deepLink}. */
    private String marker = "";

    /** Result currency passed to the provider (e.g. {@code rub}, {@code usd}). */
    private String currency = "rub";

    /** Cap the offers returned so a plan stays cheap + readable. */
    private int maxResults = 10;

    /** Aviasales/Travelpayouts data API base (flights + autocomplete live on different hosts below). */
    private String aviasalesApiUrl = "https://api.travelpayouts.com";
    /** Aviasales site host — the base for a flight buy deep link ({@code link} is relative). */
    private String aviasalesSiteUrl = "https://www.aviasales.com";
    /** Hotellook data API base (hotel cache prices). */
    private String hotellookApiUrl = "https://engine.hotellook.com";
    /** Hotellook site host — the base for a hotel buy deep link. */
    private String hotellookSiteUrl = "https://search.hotellook.com";
    /** Travelpayouts autocomplete base (place name → IATA city code). */
    private String autocompleteUrl = "https://autocomplete.travelpayouts.com";

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getMarker() { return marker; }
    public void setMarker(String marker) { this.marker = marker; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }

    public String getAviasalesApiUrl() { return aviasalesApiUrl; }
    public void setAviasalesApiUrl(String aviasalesApiUrl) { this.aviasalesApiUrl = aviasalesApiUrl; }

    public String getAviasalesSiteUrl() { return aviasalesSiteUrl; }
    public void setAviasalesSiteUrl(String aviasalesSiteUrl) { this.aviasalesSiteUrl = aviasalesSiteUrl; }

    public String getHotellookApiUrl() { return hotellookApiUrl; }
    public void setHotellookApiUrl(String hotellookApiUrl) { this.hotellookApiUrl = hotellookApiUrl; }

    public String getHotellookSiteUrl() { return hotellookSiteUrl; }
    public void setHotellookSiteUrl(String hotellookSiteUrl) { this.hotellookSiteUrl = hotellookSiteUrl; }

    public String getAutocompleteUrl() { return autocompleteUrl; }
    public void setAutocompleteUrl(String autocompleteUrl) { this.autocompleteUrl = autocompleteUrl; }

    /** True when no affiliate token is wired → the capability degrades (empty + unconfigured). */
    public boolean isConfigured() {
        return token != null && !token.isBlank();
    }
}
