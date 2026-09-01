package dev.fedorov.ailife.mcp.mediafetch;

import dev.fedorov.ailife.mcp.mediafetch.config.McpMediaFetchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpMediaFetchProperties.class)
public class McpMediaFetchApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpMediaFetchApplication.class, args);
    }
}
