package dev.fedorov.ailife.tg.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpClientsConfig {

    @Bean
    public WebClient profileWebClient(GatewayProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getServices().getProfileBaseUrl()).build();
    }

    @Bean
    public WebClient orchestratorWebClient(GatewayProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getServices().getOrchestratorBaseUrl()).build();
    }

    @Bean
    public WebClient mediaWebClient(GatewayProperties props, WebClient.Builder builder) {
        return builder.clone().baseUrl(props.getServices().getMediaBaseUrl()).build();
    }

    @Bean
    public WebClient mediaProcessingWebClient(GatewayProperties props, WebClient.Builder builder) {
        return builder.clone().baseUrl(props.getServices().getMediaProcessingBaseUrl()).build();
    }
}
