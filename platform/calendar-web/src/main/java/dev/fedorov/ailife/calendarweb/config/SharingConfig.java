package dev.fedorov.ailife.calendarweb.config;

import dev.fedorov.ailife.sharing.ProfileSharingClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the shared sharing capability's identity read (ADR-0002 slice 3b). calendar-web is a read-only web
 * service (not a domain agent), so it depends on the light {@code libs/sharing} leaf rather than
 * {@code agent-runtime}, and reads the per-person feed's household set (personal ∪ shared) through the same
 * {@link ProfileSharingClient} the agents use — retiring calendar-web's former local
 * {@code ProfileHouseholdsClient} so the member→set resolution lives in one place.
 */
@Configuration
public class SharingConfig {

    @Bean
    public ProfileSharingClient profileSharingClient(WebClient.Builder builder, CalendarWebProperties props) {
        return new ProfileSharingClient(builder.baseUrl(props.getProfileServiceUrl()).build());
    }
}
