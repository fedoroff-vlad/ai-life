package dev.fedorov.ailife.mcp.feeds;

import dev.fedorov.ailife.mcp.feeds.config.McpFeedsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpFeedsProperties.class)
public class McpFeedsApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpFeedsApplication.class, args);
    }
}
