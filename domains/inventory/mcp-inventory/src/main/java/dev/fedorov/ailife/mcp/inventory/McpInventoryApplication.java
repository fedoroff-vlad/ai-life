package dev.fedorov.ailife.mcp.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpInventoryApplication.class, args);
    }
}
