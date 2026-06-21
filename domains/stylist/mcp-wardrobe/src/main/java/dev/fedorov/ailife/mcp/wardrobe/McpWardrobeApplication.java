package dev.fedorov.ailife.mcp.wardrobe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpWardrobeApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpWardrobeApplication.class, args);
    }
}
