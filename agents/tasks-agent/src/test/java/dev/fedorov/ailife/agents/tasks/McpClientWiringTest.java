package dev.fedorov.ailife.agents.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring AI MCP client auto-config is wired into tasks-agent: the dependency is on
 * the classpath, the {@code spring.ai.mcp.client.sse.connections.mcp-tasks} block in
 * application.yml is parsed, and a {@link ToolCallbackProvider} bean is exposed for the future
 * tool dispatcher.
 *
 * <p>{@code initialized=false} registers the connection without dialling it, so the test doesn't
 * pay the ~20s eager-connect timeout — enough to prove the wiring; the live SSE round-trip is the
 * dispatcher/router PR's job. Mirrors finance-agent's {@code McpClientWiringTest} (PR33).
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.enabled=true",
        "spring.ai.mcp.client.initialized=false",
})
class McpClientWiringTest {

    @Autowired(required = false)
    ToolCallbackProvider toolCallbackProvider;

    @Test
    void toolCallbackProviderBeanIsExposedWhenMcpClientEnabled() {
        assertThat(toolCallbackProvider)
                .as("ToolCallbackProvider must be wired when spring.ai.mcp.client.enabled=true")
                .isNotNull();
    }
}
