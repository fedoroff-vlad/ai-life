package dev.fedorov.ailife.calendarweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Config for the read-only calendar view. {@link #feeds} maps a secret URL token → the household whose
 * events that feed exposes. Tokens are configured per deployment (env-bound, e.g.
 * {@code CALENDAR_WEB_FEEDS_0_TOKEN} / {@code _0_HOUSEHOLD_ID} / {@code _0_LABEL}) rather than stored —
 * a 2-person household needs a handful, and there's no write surface to manage them. The token sits in
 * the public ICS URL (Google/Yandex can't send auth on a subscription), so it must be long + unguessable.
 */
@ConfigurationProperties(prefix = "calendar-web")
public class CalendarWebProperties {

    /** Base URL of mcp-caldav (its {@code /internal/events} read passthrough). */
    private String mcpCaldavUrl = "http://mcp-caldav:8090";

    /** How far back / ahead the feed window spans from "now". */
    private int pastDays = 31;
    private int futureDays = 366;

    private List<Feed> feeds = new ArrayList<>();

    public String getMcpCaldavUrl() { return mcpCaldavUrl; }
    public void setMcpCaldavUrl(String mcpCaldavUrl) { this.mcpCaldavUrl = mcpCaldavUrl; }
    public int getPastDays() { return pastDays; }
    public void setPastDays(int pastDays) { this.pastDays = pastDays; }
    public int getFutureDays() { return futureDays; }
    public void setFutureDays(int futureDays) { this.futureDays = futureDays; }
    public List<Feed> getFeeds() { return feeds; }
    public void setFeeds(List<Feed> feeds) { this.feeds = feeds; }

    /** Resolve a request token to its feed (constant-ish lookup over a tiny list). */
    public Optional<Feed> feedByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return feeds.stream().filter(f -> token.equals(f.getToken())).findFirst();
    }

    public static class Feed {
        /** The secret URL token (the {token} in /ics/{token}.ics). */
        private String token;
        /** The household whose events this feed exposes. */
        private UUID householdId;
        /** Human label for the calendar name (e.g. "Vlad", "Family"). */
        private String label = "ai-life";

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public UUID getHouseholdId() { return householdId; }
        public void setHouseholdId(UUID householdId) { this.householdId = householdId; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }
}
