package dev.fedorov.ailife.mcp.mediaprocessing;

import dev.fedorov.ailife.mcp.mediaprocessing.config.McpMediaProcessingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpMediaProcessingProperties.class)
public class McpMediaProcessingApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpMediaProcessingApplication.class, args);
    }
}
