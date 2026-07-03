package dev.fedorov.ailife.agents.notes.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound HTTP clients specific to the notes agent (the shared profile / notifier / memory-service
 * clients come from {@code agent-runtime}). Currently only scheduler-service, for the R-c
 * auto-registration of the household resurface cron. Each bean {@code .clone()}s the shared builder and
 * pins its own base URL to avoid base-URL leakage.
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient schedulerWebClient(WebClient.Builder builder, NotesAgentProperties props) {
        return builder.clone().baseUrl(props.getSchedulerUrl()).build();
    }
}
