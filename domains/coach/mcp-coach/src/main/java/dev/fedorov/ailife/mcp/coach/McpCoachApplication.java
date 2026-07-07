package dev.fedorov.ailife.mcp.coach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpCoachApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpCoachApplication.class, args);
    }
}
