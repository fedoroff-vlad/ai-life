package dev.fedorov.ailife.llm;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration
@EnableConfigurationProperties(LlmClientProperties.class)
public class LlmClientAutoConfiguration {

    @Bean
    public WebClient llmGatewayWebClient(LlmClientProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getBaseUrl()).build();
    }

    @Bean
    public LlmClient llmClient(WebClient llmGatewayWebClient) {
        return new LlmClient(llmGatewayWebClient);
    }
}
