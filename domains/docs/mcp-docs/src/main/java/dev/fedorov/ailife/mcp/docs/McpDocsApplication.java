package dev.fedorov.ailife.mcp.docs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpDocsApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpDocsApplication.class, args);
    }
}
