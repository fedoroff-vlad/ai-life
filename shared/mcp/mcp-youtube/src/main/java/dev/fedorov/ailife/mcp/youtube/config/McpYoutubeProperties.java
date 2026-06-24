package dev.fedorov.ailife.mcp.youtube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "youtube")
public class McpYoutubeProperties {

    /**
     * YouTube Data API v3 base URL — where {@code youtube_trends} reads the search response
     * ({@code GET /youtube/v3/search}). Overridable so a MockWebServer can stand in for it in tests.
     */
    private String apiBaseUrl = "https://www.googleapis.com/youtube/v3";

    /**
     * YouTube Data API v3 key (free quota; set via env, never committed). When blank the source
     * returns no hits rather than calling the API — so the agent's gather soft-fails this source
     * gracefully when no key is configured (e.g. CI), instead of erroring.
     */
    private String apiKey = "";

    /** Default number of trend hits when the caller doesn't cap it. */
    private int maxResults = 5;

    /**
     * Which video-trends source to wire: {@code youtubedata} (default, YouTube Data API v3). Behind
     * the {@code VideoTrendsSource} interface so a sibling source can replace it later via env with no
     * caller change (mirrors mcp-food-data / mcp-market-data's source selector).
     */
    private String source = "youtubedata";

    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
