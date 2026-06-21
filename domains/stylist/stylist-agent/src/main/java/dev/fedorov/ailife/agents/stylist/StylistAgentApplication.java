package dev.fedorov.ailife.agents.stylist;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.stylist.config.StylistAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(StylistAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class StylistAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(StylistAgentApplication.class, args);
    }
}
