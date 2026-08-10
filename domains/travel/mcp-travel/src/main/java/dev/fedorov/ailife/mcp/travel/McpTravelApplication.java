package dev.fedorov.ailife.mcp.travel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpTravelApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpTravelApplication.class, args);
    }
}
