package dev.fedorov.ailife.mcp.fooddata;

import dev.fedorov.ailife.mcp.fooddata.config.McpFoodDataProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpFoodDataProperties.class)
public class McpFoodDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpFoodDataApplication.class, args);
    }
}
