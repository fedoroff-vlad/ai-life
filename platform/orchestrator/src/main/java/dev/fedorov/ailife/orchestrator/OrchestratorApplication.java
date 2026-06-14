package dev.fedorov.ailife.orchestrator;

import dev.fedorov.ailife.orchestrator.agent.AgentRegistryProperties;
import dev.fedorov.ailife.orchestrator.conversation.ConversationProperties;
import dev.fedorov.ailife.orchestrator.memory.MemoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AgentRegistryProperties.class, MemoryProperties.class,
        ConversationProperties.class})
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
