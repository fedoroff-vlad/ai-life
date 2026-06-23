package dev.fedorov.ailife.agents.chef;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.chef.config.ChefAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(ChefAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class ChefAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChefAgentApplication.class, args);
    }
}
