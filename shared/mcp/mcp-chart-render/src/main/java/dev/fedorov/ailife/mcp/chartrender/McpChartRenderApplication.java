package dev.fedorov.ailife.mcp.chartrender;

import dev.fedorov.ailife.mcp.chartrender.config.McpChartRenderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = McpChartRenderProperties.class)
public class McpChartRenderApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpChartRenderApplication.class, args);
    }
}
