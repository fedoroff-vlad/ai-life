package dev.fedorov.ailife.mcp.mediafetch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "media-fetch")
public class McpMediaFetchProperties {

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
