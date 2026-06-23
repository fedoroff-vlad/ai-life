package dev.fedorov.ailife.agents.nutritionist;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.nutritionist.config.NutritionistAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(NutritionistAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class NutritionistAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(NutritionistAgentApplication.class, args);
    }
}
