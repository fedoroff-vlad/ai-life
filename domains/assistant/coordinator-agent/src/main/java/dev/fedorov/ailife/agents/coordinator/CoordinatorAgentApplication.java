package dev.fedorov.ailife.agents.coordinator;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.coordinator.config.CoordinatorAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(CoordinatorAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class CoordinatorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoordinatorAgentApplication.class, args);
    }
}
