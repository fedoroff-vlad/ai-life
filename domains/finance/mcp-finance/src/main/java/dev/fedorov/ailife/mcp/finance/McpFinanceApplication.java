package dev.fedorov.ailife.mcp.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpFinanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpFinanceApplication.class, args);
    }
}
