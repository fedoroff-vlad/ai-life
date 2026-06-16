package dev.fedorov.ailife.agents.finance;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the Spring AI MCP client auto-config is wired into
 * finance-agent's context: the dependency is on the classpath, the
 * {@code spring.ai.mcp.client.sse.connections.*} block in application.yml is
 * parsed, and a {@link ToolCallbackProvider} bean is exposed for downstream
 * tool-call dispatch.
 *
 * <p>The SDK normally dials each configured server eagerly at context refresh
 * (per-server {@code request-timeout}, default 20s); we set
 * {@code initialized=false} here so the test doesn't pay that cost — the
 * connections are registered but not dialled until first tool use. This is
 * enough to prove the wire correctness; the actual SSE round-trip is
 * exercised by the future intent-flow PR that puts {@code import_moneypro_csv}
 * behind a user-facing intent.
 *
 * <p>Production keeps {@code initialized=true} (the default) so a missing
 * mcp-money-pro-import process surfaces at agent boot, not on the first
 * import attempt — opinionated, consistent with the fail-fast loader
 * hardening PR32 introduced.
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
        // ToolCallbackProvider is the contract downstream code (intent
        // controller, eventually a ChatClient) will consume. Its presence
        // proves the spring-ai-starter-mcp-client-webflux auto-config picked
        // up the application.yml connections block and registered the bean.
        assertThat(toolCallbackProvider)
                .as("ToolCallbackProvider must be wired when spring.ai.mcp.client.enabled=true")
                .isNotNull();
    }
}
