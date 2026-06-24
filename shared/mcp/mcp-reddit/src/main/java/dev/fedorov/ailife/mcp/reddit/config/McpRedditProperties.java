package dev.fedorov.ailife.mcp.reddit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reddit")
public class McpRedditProperties {

    /**
     * Reddit OAuth base URL — where {@code reddit_trends} reads listings with the bearer token
     * ({@code GET /r/{sub}/hot}, {@code /r/{sub}/search}, {@code /search}). Overridable so a
     * MockWebServer can stand in for it in tests.
     */
    private String apiBaseUrl = "https://oauth.reddit.com";

    /**
     * Reddit auth base URL — where the app-only token is minted
     * ({@code POST /api/v1/access_token}, grant {@code client_credentials}). Separate host from the
     * API in production; a test points both at one MockWebServer.
     */
    private String authBaseUrl = "https://www.reddit.com";

    /** Reddit app client id (free; set via env, never committed). Blank → the source returns no hits. */
    private String clientId = "";

    /** Reddit app client secret (free; set via env, never committed). Blank → the source returns no hits. */
    private String clientSecret = "";

    /**
     * The {@code User-Agent} Reddit's API policy requires (it rate-limits / blocks generic agents).
     * Identify the app + a contact.
     */
    private String userAgent = "ai-life/mcp-reddit 0.0.1 (by /u/ai-life)";

    /** Default number of trend hits when the caller doesn't cap it. */
    private int maxResults = 5;

    /**
     * Which social-trends source to wire: {@code redditapi} (default, the Reddit API). Behind the
     * {@code SocialTrendsSource} interface so a sibling source can replace it later via env with no
     * caller change (mirrors mcp-youtube's source selector).
     */
    private String source = "redditapi";

    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public String getAuthBaseUrl() { return authBaseUrl; }
    public void setAuthBaseUrl(String authBaseUrl) { this.authBaseUrl = authBaseUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
