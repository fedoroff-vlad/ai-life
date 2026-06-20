package dev.fedorov.ailife.mcp.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp-web")
public class McpWebProperties {

    /** SearXNG base URL — where {@code web_search} sends queries (JSON format). */
    private String searxngUrl = "http://searxng:8080";

    /**
     * Which search engine to wire: {@code searxng} (default, self-hosted). Behind the
     * {@code SearchEngine} interface so {@code tavily}/{@code brave} can replace it later
     * via env with no caller change (mirrors the OCR-engine selector).
     */
    private String searchEngine = "searxng";

    /** Default number of hits {@code web_search} returns when the caller doesn't specify. */
    private int defaultLimit = 8;

    /** Hard cap on hits, so a caller can't ask the engine for an unbounded page. */
    private int maxLimit = 20;

    public String getSearxngUrl() { return searxngUrl; }
    public void setSearxngUrl(String searxngUrl) { this.searxngUrl = searxngUrl; }
    public String getSearchEngine() { return searchEngine; }
    public void setSearchEngine(String searchEngine) { this.searchEngine = searchEngine; }
    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }
    public int getMaxLimit() { return maxLimit; }
    public void setMaxLimit(int maxLimit) { this.maxLimit = maxLimit; }
}
