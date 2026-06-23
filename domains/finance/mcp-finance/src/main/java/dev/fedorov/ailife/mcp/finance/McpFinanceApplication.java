package dev.fedorov.ailife.mcp.finance;

import dev.fedorov.ailife.bus.EventBusConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(EventBusConfig.class)   // provides the OutboxPublisher for the basket.captured drop-point (IA-a)
public class McpFinanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpFinanceApplication.class, args);
    }
}
