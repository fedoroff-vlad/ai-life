package dev.fedorov.ailife.mcp.nutrition;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpNutritionApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpNutritionApplication.class, args);
    }
}
