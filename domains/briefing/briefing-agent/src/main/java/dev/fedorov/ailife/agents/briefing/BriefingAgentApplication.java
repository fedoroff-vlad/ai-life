package dev.fedorov.ailife.agents.briefing;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.briefing.config.BriefingAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(BriefingAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class BriefingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(BriefingAgentApplication.class, args);
    }
}
