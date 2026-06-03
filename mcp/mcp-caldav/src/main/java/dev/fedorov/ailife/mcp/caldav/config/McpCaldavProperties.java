package dev.fedorov.ailife.mcp.caldav.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "caldav")
public class McpCaldavProperties {

    /** Radicale base URL, e.g. {@code http://radicale:5232}. */
    private String url = "http://localhost:5232";

    /** Optional basic-auth credentials. Radicale dev config uses {@code auth=none}. */
    private String user = "";
    private String password = "";

    /** Calendar collection name owned by each household. Created on demand. */
    private String defaultCalendar = "ours";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDefaultCalendar() { return defaultCalendar; }
    public void setDefaultCalendar(String defaultCalendar) { this.defaultCalendar = defaultCalendar; }

    public boolean hasCredentials() {
        return user != null && !user.isBlank();
    }
}
