package dev.fedorov.ailife.agents.notes;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.notes.config.NotesAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(NotesAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class NotesAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotesAgentApplication.class, args);
    }
}
