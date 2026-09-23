package dev.fedorov.ailife.agents.inventory;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.inventory.config.InventoryAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(AgentRuntimeConfig.class)
@EnableConfigurationProperties(InventoryAgentProperties.class)
public class InventoryAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryAgentApplication.class, args);
    }
}
