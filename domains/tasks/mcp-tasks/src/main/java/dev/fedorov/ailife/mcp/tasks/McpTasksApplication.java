package dev.fedorov.ailife.mcp.tasks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpTasksApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpTasksApplication.class, args);
    }
}
