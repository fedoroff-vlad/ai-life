package dev.fedorov.ailife.agents.researcher;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.researcher.config.ResearcherAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(ResearcherAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class ResearcherAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResearcherAgentApplication.class, args);
    }
}
