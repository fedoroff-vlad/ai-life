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

    /** {@code fetch_url} connect/read timeout (ms). */
    private int fetchTimeoutMs = 8000;

    /** {@code fetch_url} max extracted characters; longer text is truncated (flag set). */
    private int fetchMaxChars = 8000;

    /**
     * Which video-transcript engine to wire: {@code yt-dlp} (default, needs the binary in the
     * image) or {@code stub} (native-free marker, for the wiring test / degraded boxes).
     */
    private String transcriptEngine = "yt-dlp";

    /** Path/name of the yt-dlp binary (the image bundles it as {@code yt-dlp}). */
    private String ytDlpBin = "yt-dlp";

    /** Preferred subtitle languages (yt-dlp {@code --sub-langs}, comma-separated, regex ok). */
    private String transcriptLangs = "en.*,ru.*";

    /** yt-dlp subprocess timeout (seconds). */
    private int transcriptTimeoutSec = 60;

    /** {@code transcribe_video} max characters; longer text is truncated (flag set). */
    private int transcriptMaxChars = 12000;

    public String getSearxngUrl() { return searxngUrl; }
    public void setSearxngUrl(String searxngUrl) { this.searxngUrl = searxngUrl; }
    public String getSearchEngine() { return searchEngine; }
    public void setSearchEngine(String searchEngine) { this.searchEngine = searchEngine; }
    public int getDefaultLimit() { return defaultLimit; }
    public void setDefaultLimit(int defaultLimit) { this.defaultLimit = defaultLimit; }
    public int getMaxLimit() { return maxLimit; }
    public void setMaxLimit(int maxLimit) { this.maxLimit = maxLimit; }
    public int getFetchTimeoutMs() { return fetchTimeoutMs; }
    public void setFetchTimeoutMs(int fetchTimeoutMs) { this.fetchTimeoutMs = fetchTimeoutMs; }
    public int getFetchMaxChars() { return fetchMaxChars; }
    public void setFetchMaxChars(int fetchMaxChars) { this.fetchMaxChars = fetchMaxChars; }

    public String getTranscriptEngine() { return transcriptEngine; }
    public void setTranscriptEngine(String transcriptEngine) { this.transcriptEngine = transcriptEngine; }
    public String getYtDlpBin() { return ytDlpBin; }
    public void setYtDlpBin(String ytDlpBin) { this.ytDlpBin = ytDlpBin; }
    public String getTranscriptLangs() { return transcriptLangs; }
    public void setTranscriptLangs(String transcriptLangs) { this.transcriptLangs = transcriptLangs; }
    public int getTranscriptTimeoutSec() { return transcriptTimeoutSec; }
    public void setTranscriptTimeoutSec(int transcriptTimeoutSec) { this.transcriptTimeoutSec = transcriptTimeoutSec; }
    public int getTranscriptMaxChars() { return transcriptMaxChars; }
    public void setTranscriptMaxChars(int transcriptMaxChars) { this.transcriptMaxChars = transcriptMaxChars; }
}
