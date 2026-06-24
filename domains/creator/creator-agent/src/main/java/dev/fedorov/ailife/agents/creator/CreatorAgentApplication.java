package dev.fedorov.ailife.agents.creator;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.creator.config.CreatorAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(CreatorAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class CreatorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreatorAgentApplication.class, args);
    }
}
