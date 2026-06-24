package dev.fedorov.ailife.mcp.creator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpCreatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpCreatorApplication.class, args);
    }
}
