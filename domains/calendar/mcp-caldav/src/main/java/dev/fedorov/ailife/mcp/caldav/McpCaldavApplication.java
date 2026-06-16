package dev.fedorov.ailife.mcp.caldav;

import dev.fedorov.ailife.mcp.caldav.config.McpCaldavProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpCaldavProperties.class)
public class McpCaldavApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpCaldavApplication.class, args);
    }
}
