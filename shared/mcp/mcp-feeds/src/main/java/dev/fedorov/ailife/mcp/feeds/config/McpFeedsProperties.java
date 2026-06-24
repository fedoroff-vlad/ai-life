package dev.fedorov.ailife.mcp.feeds.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "feeds")
public class McpFeedsProperties {

    /**
     * Base URL for public Telegram channels — {@code feed_items} reads the web preview
     * ({@code GET /s/{channel}}) and parses it with jsoup. Overridable so a MockWebServer can stand in
     * for it in tests. (RSS/Atom feed URLs are absolute and fetched as given — no base URL needed.)
     */
    private String telegramBaseUrl = "https://t.me";

    /**
     * The {@code User-Agent} sent when fetching feeds / channel pages. Some hosts block generic
     * clients; identify the app.
     */
    private String userAgent = "ai-life/mcp-feeds 0.0.1 (+https://github.com/vlad94fed)";

    /** Default number of items when the caller doesn't cap it. */
    private int maxResults = 5;

    /**
     * Which feed source to wire: {@code romejsoup} (default — Rome for RSS/Atom, jsoup for Telegram).
     * Behind the {@code FeedSource} interface so a sibling source can replace it later via env with no
     * caller change (mirrors mcp-youtube / mcp-reddit's source selector).
     */
    private String source = "romejsoup";

    public String getTelegramBaseUrl() { return telegramBaseUrl; }
    public void setTelegramBaseUrl(String telegramBaseUrl) { this.telegramBaseUrl = telegramBaseUrl; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
