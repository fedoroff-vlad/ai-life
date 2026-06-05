package dev.fedorov.ailife.mcp.icsimport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "icsimport")
public class McpIcsImportProperties {

    /** Radicale base URL, e.g. {@code http://radicale:5232}. */
    private String caldavUrl = "http://localhost:5232";

    /** Optional basic-auth credentials. Radicale dev config uses {@code auth=none}. */
    private String caldavUser = "";
    private String caldavPassword = "";

    /**
     * Calendar-name prefix for read-only external collections. The full per-subscription
     * collection name is {@code <prefix>-<slug>} (e.g. {@code external-work}). Keeping a
     * prefix lets CalDAV consumers tell external feeds from {@code ours} at a glance.
     */
    private String collectionPrefix = "external";

    /** Hard cap on a single ICS body to avoid pulling pathological feeds into memory. */
    private int maxIcsBytes = 5 * 1024 * 1024;

    public String getCaldavUrl() { return caldavUrl; }
    public void setCaldavUrl(String caldavUrl) { this.caldavUrl = caldavUrl; }
    public String getCaldavUser() { return caldavUser; }
    public void setCaldavUser(String caldavUser) { this.caldavUser = caldavUser; }
    public String getCaldavPassword() { return caldavPassword; }
    public void setCaldavPassword(String caldavPassword) { this.caldavPassword = caldavPassword; }
    public String getCollectionPrefix() { return collectionPrefix; }
    public void setCollectionPrefix(String collectionPrefix) { this.collectionPrefix = collectionPrefix; }
    public int getMaxIcsBytes() { return maxIcsBytes; }
    public void setMaxIcsBytes(int maxIcsBytes) { this.maxIcsBytes = maxIcsBytes; }

    public boolean hasCaldavCredentials() {
        return caldavUser != null && !caldavUser.isBlank();
    }
}
