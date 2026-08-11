package dev.fedorov.ailife.agents.travel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents): {@code mcpTravel} (its data) +
 * {@code mcpWeather} (geocoding + the TR-d climate season substrate) + {@code mcpWeb} (TR-d
 * destination research). The shared {@code profile/notifier/memory} WebClients live in
 * {@code agent-runtime}'s {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpTravelWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpTravelUrl()).build();
    }

    @Bean
    public WebClient mcpWeatherWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWeatherUrl()).build();
    }

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, TravelAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }
}
