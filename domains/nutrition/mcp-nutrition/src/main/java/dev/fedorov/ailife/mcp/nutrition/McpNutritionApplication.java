package dev.fedorov.ailife.mcp.nutrition;

import dev.fedorov.ailife.bus.EventBusConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ConfigurationPropertiesScan
@Import(EventBusConfig.class)   // EventBusProperties + listener wiring for the basket.captured consumer (IA-b)
public class McpNutritionApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpNutritionApplication.class, args);
    }
}
