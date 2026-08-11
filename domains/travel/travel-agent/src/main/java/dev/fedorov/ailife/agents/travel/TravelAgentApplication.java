package dev.fedorov.ailife.agents.travel;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.travel.config.TravelAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(TravelAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class TravelAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelAgentApplication.class, args);
    }
}
