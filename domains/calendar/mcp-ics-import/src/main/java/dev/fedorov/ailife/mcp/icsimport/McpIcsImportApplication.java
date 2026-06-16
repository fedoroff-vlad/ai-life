package dev.fedorov.ailife.mcp.icsimport;

import dev.fedorov.ailife.mcp.icsimport.config.McpIcsImportProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpIcsImportProperties.class)
public class McpIcsImportApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpIcsImportApplication.class, args);
    }
}
