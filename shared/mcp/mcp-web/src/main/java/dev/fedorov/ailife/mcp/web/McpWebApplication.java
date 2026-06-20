package dev.fedorov.ailife.mcp.web;

import dev.fedorov.ailife.mcp.web.config.McpWebProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpWebProperties.class)
public class McpWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpWebApplication.class, args);
    }
}
