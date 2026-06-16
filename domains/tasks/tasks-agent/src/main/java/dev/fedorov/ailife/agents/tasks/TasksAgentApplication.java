package dev.fedorov.ailife.agents.tasks;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.tasks.config.TasksAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Tasks/GTD agent. Skeleton slice: manifest + intent are real; triggers are a stub (no skills
 * shipped yet — they 404). The MCP-tool wiring and the skill-driven flows land with the first
 * skill, mirroring how finance-agent grew. The profile/notifier/memory WebClients exist already
 * because {@code AgentRuntimeConfig} binds its shared clients on them.
 */
@SpringBootApplication
@EnableConfigurationProperties(TasksAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class TasksAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TasksAgentApplication.class, args);
    }
}
