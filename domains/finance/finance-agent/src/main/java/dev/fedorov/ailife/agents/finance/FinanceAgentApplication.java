package dev.fedorov.ailife.agents.finance;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.finance.config.FinanceAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(FinanceAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class FinanceAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinanceAgentApplication.class, args);
    }
}
