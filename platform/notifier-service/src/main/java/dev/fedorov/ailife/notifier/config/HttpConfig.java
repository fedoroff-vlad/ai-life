package dev.fedorov.ailife.notifier.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    @Bean
    public WebClient profileWebClient(NotifierProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getProfileBaseUrl()).build();
    }

    @Bean
    public WebClient gatewayWebClient(NotifierProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getGatewayBaseUrl()).build();
    }
}
