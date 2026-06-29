package dev.fedorov.ailife.calendarweb;

import dev.fedorov.ailife.calendarweb.config.CalendarWebProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Read-only calendar view (#195). Serves per-person <b>ICS feeds</b> over the {@code mcp-caldav}
 * read passthrough ({@code GET /internal/events}) so each household member can subscribe their own
 * Apple / Google / Yandex calendar to the events the system creates. No DB, no write path — writes
 * keep going through the calendar-agent.
 */
@SpringBootApplication
@EnableConfigurationProperties(CalendarWebProperties.class)
public class CalendarWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalendarWebApplication.class, args);
    }
}
