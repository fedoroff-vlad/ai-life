package dev.fedorov.ailife.agents.calendar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound HTTP destinations the calendar-agent talks to. Both URLs are
 * required for the trigger fan-out flow (profile lookup → notifier send).
 */
@ConfigurationProperties(prefix = "calendar-agent")
public class CalendarAgentProperties {

    private String profileServiceUrl = "http://profile-service:8082";
    private String notifierUrl = "http://notifier-service:8084";

    public String getProfileServiceUrl() { return profileServiceUrl; }
    public void setProfileServiceUrl(String profileServiceUrl) {
        this.profileServiceUrl = profileServiceUrl;
    }

    public String getNotifierUrl() { return notifierUrl; }
    public void setNotifierUrl(String notifierUrl) { this.notifierUrl = notifierUrl; }
}
