package dev.fedorov.ailife.mcp.reddit;

import dev.fedorov.ailife.mcp.reddit.config.McpRedditProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpRedditProperties.class)
public class McpRedditApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpRedditApplication.class, args);
    }
}
