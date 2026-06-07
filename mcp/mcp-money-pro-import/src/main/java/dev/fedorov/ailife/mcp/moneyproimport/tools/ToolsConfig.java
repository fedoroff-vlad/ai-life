package dev.fedorov.ailife.mcp.moneyproimport.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolsConfig {

    @Bean
    public ToolCallbackProvider moneyProImportTools(MoneyProImportMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
