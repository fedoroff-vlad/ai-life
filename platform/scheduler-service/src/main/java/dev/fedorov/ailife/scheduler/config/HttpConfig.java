package dev.fedorov.ailife.scheduler.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpConfig {

    @Bean
    public WebClient orchestratorWebClient(SchedulerProperties props, WebClient.Builder builder) {
        return builder.baseUrl(props.getOrchestratorBaseUrl()).build();
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
