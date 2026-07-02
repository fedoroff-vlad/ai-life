package dev.fedorov.ailife.agents.docs;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.docs.config.DocsAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(DocsAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class DocsAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocsAgentApplication.class, args);
    }
}
