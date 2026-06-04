package dev.fedorov.ailife.notifier.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notifier")
public class NotifierProperties {

    private String profileBaseUrl = "http://profile-service:8082";
    private String gatewayBaseUrl = "http://gateway-telegram:8080";
    /** Bearer token shared with gateway-telegram for {@code /internal/send}. */
    private String internalApiToken = "";

    public String getProfileBaseUrl() { return profileBaseUrl; }
    public void setProfileBaseUrl(String profileBaseUrl) { this.profileBaseUrl = profileBaseUrl; }
    public String getGatewayBaseUrl() { return gatewayBaseUrl; }
    public void setGatewayBaseUrl(String gatewayBaseUrl) { this.gatewayBaseUrl = gatewayBaseUrl; }
    public String getInternalApiToken() { return internalApiToken; }
    public void setInternalApiToken(String internalApiToken) { this.internalApiToken = internalApiToken; }
}
