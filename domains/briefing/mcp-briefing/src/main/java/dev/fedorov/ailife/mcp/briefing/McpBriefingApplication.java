package dev.fedorov.ailife.mcp.briefing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpBriefingApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpBriefingApplication.class, args);
    }
}
