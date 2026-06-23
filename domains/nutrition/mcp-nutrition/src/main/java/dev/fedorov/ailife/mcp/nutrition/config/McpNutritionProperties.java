package dev.fedorov.ailife.mcp.nutrition.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound config for mcp-nutrition. {@code nutritionistAgentUrl} is where the bus consumer (IA-b)
 * forwards a {@code basket.captured} event — the only place this domain-MCP reaches outward (it
 * otherwise just owns the {@code nutrition} schema).
 */
@ConfigurationProperties(prefix = "mcp-nutrition")
public class McpNutritionProperties {

    private String nutritionistAgentUrl = "http://nutritionist-agent:8105";

    public String getNutritionistAgentUrl() { return nutritionistAgentUrl; }
    public void setNutritionistAgentUrl(String nutritionistAgentUrl) {
        this.nutritionistAgentUrl = nutritionistAgentUrl;
    }
}
