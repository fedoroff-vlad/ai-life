package dev.fedorov.ailife.mcp.marketdata;

import dev.fedorov.ailife.mcp.marketdata.config.McpMarketDataProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpMarketDataProperties.class)
public class McpMarketDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpMarketDataApplication.class, args);
    }
}
