package dev.fedorov.ailife.mcp.travel.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolsConfig {

    @Bean
    public ToolCallbackProvider travelTools(TravelMcpTools profileTools, TripMcpTools tripTools,
                                            RouteMcpTools routeTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(profileTools, tripTools, routeTools)
                .build();
    }
}
