package dev.fedorov.ailife.agents.coach;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.coach.config.CoachAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(CoachAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class CoachAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoachAgentApplication.class, args);
    }
}
