package dev.fedorov.ailife.agents.docs.config;

import dev.fedorov.ailife.agentruntime.config.SharedClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the docs agent talks to. {@code mcpDocsUrl} is its own data (the docs
 * domain-MCP — store + search); {@code mcpMediaProcessingUrl} is the shared media capability whose
 * {@code /internal/ocr} passthrough turns a document photo into text (D-c ingest). The profile /
 * notifier / memory URLs back the shared {@code agent-runtime} clients every agent imports.
 */
@ConfigurationProperties(prefix = "docs-agent")
public class DocsAgentProperties implements SharedClientProperties {

    private String mcpDocsUrl = "http://mcp-docs:8116";
    private String mcpMediaProcessingUrl = "http://mcp-media-processing:8097";
    private String publicMediaBaseUrl = "http://media-service:8088";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";
    /**
     * RU-4 photo robustness (#489): a captionless document photo whose OCR confidence (0..1) is
     * <b>known and below</b> this value — or whose OCR text is empty — is treated as unreadable, so
     * the archiver asks for a clearer shot instead of filing a blank/garbage corpus. A {@code null}
     * confidence (no signal) is "unknown", never low, so it still archives (back-compat). Deliberately
     * conservative so a merely-mediocre but readable scan is not rejected. Internal tunable — override
     * via {@code docs-agent.ocr-min-confidence} / env {@code DOCS_AGENT_OCRMINCONFIDENCE}.
     */
    private double ocrMinConfidence = 0.4;

    public String getMcpDocsUrl() { return mcpDocsUrl; }
    public void setMcpDocsUrl(String mcpDocsUrl) { this.mcpDocsUrl = mcpDocsUrl; }

    /**
     * Public base URL a stored document's open-link is built from ({@code <base>/v1/media/{mediaId}}),
     * so a search hit can be opened on any device. Defaults to the internal media-service URL; set to a
     * publicly-reachable gateway base in a real deployment (mirrors briefing's public-media-base-url).
     */
    public String getPublicMediaBaseUrl() { return publicMediaBaseUrl; }
    public void setPublicMediaBaseUrl(String publicMediaBaseUrl) {
        this.publicMediaBaseUrl = publicMediaBaseUrl;
    }

    public String getMcpMediaProcessingUrl() { return mcpMediaProcessingUrl; }
    public void setMcpMediaProcessingUrl(String mcpMediaProcessingUrl) {
        this.mcpMediaProcessingUrl = mcpMediaProcessingUrl;
    }

    public String getProfileServiceUrl() { return profileServiceUrl; }
    public void setProfileServiceUrl(String profileServiceUrl) {
        this.profileServiceUrl = profileServiceUrl;
    }

    public String getNotifierUrl() { return notifierUrl; }
    public void setNotifierUrl(String notifierUrl) { this.notifierUrl = notifierUrl; }

    public String getMemoryServiceUrl() { return memoryServiceUrl; }
    public void setMemoryServiceUrl(String memoryServiceUrl) {
        this.memoryServiceUrl = memoryServiceUrl;
    }

    public double getOcrMinConfidence() { return ocrMinConfidence; }
    public void setOcrMinConfidence(double ocrMinConfidence) {
        this.ocrMinConfidence = ocrMinConfidence;
    }
}
