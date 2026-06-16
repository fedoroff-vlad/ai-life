package dev.fedorov.ailife.agents.calendar;

import dev.fedorov.ailife.agentruntime.config.AgentRuntimeConfig;
import dev.fedorov.ailife.agents.calendar.config.CalendarAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableConfigurationProperties(CalendarAgentProperties.class)
@Import(AgentRuntimeConfig.class)
public class CalendarAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalendarAgentApplication.class, args);
    }
}
