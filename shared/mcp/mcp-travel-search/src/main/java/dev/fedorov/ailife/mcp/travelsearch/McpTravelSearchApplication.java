package dev.fedorov.ailife.mcp.travelsearch;

import dev.fedorov.ailife.mcp.travelsearch.config.McpTravelSearchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpTravelSearchProperties.class)
public class McpTravelSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpTravelSearchApplication.class, args);
    }
}
