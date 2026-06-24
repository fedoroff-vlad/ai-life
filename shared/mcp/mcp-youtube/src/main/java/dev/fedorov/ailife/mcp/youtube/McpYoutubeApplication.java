package dev.fedorov.ailife.mcp.youtube;

import dev.fedorov.ailife.mcp.youtube.config.McpYoutubeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpYoutubeProperties.class)
public class McpYoutubeApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpYoutubeApplication.class, args);
    }
}
