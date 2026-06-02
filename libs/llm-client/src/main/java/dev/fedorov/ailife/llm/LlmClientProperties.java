package dev.fedorov.ailife.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ailife.llm-client")
public class LlmClientProperties {

    /** Base URL of llm-gateway, e.g. {@code http://llm-gateway:8081}. */
    private String baseUrl = "http://llm-gateway:8081";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
