package dev.fedorov.ailife.memory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound HTTP clients. memory-service is a servlet app but reuses the reactive
 * {@link WebClient} (on the classpath via llm-client) in blocking style, matching
 * the convention in the other platform services (e.g. notifier-service).
 */
@Configuration
public class HttpConfig {

    @Bean
    public WebClient profileWebClient(MemoryServiceProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getProfileBaseUrl()).build();
    }

    @Bean
    public WebClient conversationWebClient(MemoryServiceProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getConversationBaseUrl()).build();
    }

    @Bean
    public WebClient notifierWebClient(MemoryServiceProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getNotifierBaseUrl()).build();
    }
}
