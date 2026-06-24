package dev.fedorov.ailife.agents.nutritionist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the nutritionist talks to. {@code mcpNutritionUrl} is its own data
 * (the nutrition domain-MCP); {@code mcpMediaProcessingUrl} + {@code mcpWebUrl} + {@code mcpFoodDataUrl}
 * are shared capabilities (vision caption / store lookup / nutrition-facts lookup) the deterministic
 * flows call over their {@code /internal/*} passthroughs. The profile / notifier / memory URLs back
 * the shared {@code agent-runtime} clients every agent imports.
 */
@ConfigurationProperties(prefix = "nutritionist-agent")
public class NutritionistAgentProperties {

    private String mcpNutritionUrl = "http://mcp-nutrition:8104";
    private String mcpMediaProcessingUrl = "http://mcp-media-processing:8097";
    private String mcpWebUrl = "http://mcp-web:8098";
    private String mcpFoodDataUrl = "http://mcp-food-data:8107";
    private String mediaServiceUrl = "http://media-service:8088";
    private String orchestratorUrl = "http://orchestrator:8083";
    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";
    private String memoryServiceUrl = "http://memory-service:8087";

    /**
     * Public base URL a stored deliverable link is built from (so the user can open it on any
     * device). Defaults to the internal media-service URL; set to a publicly-reachable gateway base
     * in a real deployment. The link is {@code <base>/v1/media/{id}}.
     */
    private String publicMediaBaseUrl = "http://media-service:8088";

    public String getMcpNutritionUrl() { return mcpNutritionUrl; }
    public void setMcpNutritionUrl(String mcpNutritionUrl) { this.mcpNutritionUrl = mcpNutritionUrl; }

    public String getMediaServiceUrl() { return mediaServiceUrl; }
    public void setMediaServiceUrl(String mediaServiceUrl) { this.mediaServiceUrl = mediaServiceUrl; }

    public String getOrchestratorUrl() { return orchestratorUrl; }
    public void setOrchestratorUrl(String orchestratorUrl) { this.orchestratorUrl = orchestratorUrl; }

    public String getPublicMediaBaseUrl() { return publicMediaBaseUrl; }
    public void setPublicMediaBaseUrl(String publicMediaBaseUrl) {
        this.publicMediaBaseUrl = publicMediaBaseUrl;
    }

    public String getMcpMediaProcessingUrl() { return mcpMediaProcessingUrl; }
    public void setMcpMediaProcessingUrl(String mcpMediaProcessingUrl) {
        this.mcpMediaProcessingUrl = mcpMediaProcessingUrl;
    }

    public String getMcpWebUrl() { return mcpWebUrl; }
    public void setMcpWebUrl(String mcpWebUrl) { this.mcpWebUrl = mcpWebUrl; }

    public String getMcpFoodDataUrl() { return mcpFoodDataUrl; }
    public void setMcpFoodDataUrl(String mcpFoodDataUrl) { this.mcpFoodDataUrl = mcpFoodDataUrl; }

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
}
