package dev.fedorov.ailife.agents.finance.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ToolDispatcher}. We feed it a hand-rolled
 * {@link ToolCallbackProvider} so we don't depend on a live MCP server — the
 * full SSE round-trip is the wire-level concern of Spring AI's
 * {@code spring-ai-starter-mcp-client-webflux} (proven in {@code
 * McpClientWiringTest}). What this test covers is the dispatcher's lookup +
 * arg-passthrough + error contract.
 */
class ToolDispatcherTest {

    @Test
    void dispatchInvokesMatchingCallbackAndReturnsItsResult() {
        AtomicReference<String> seenArgs = new AtomicReference<>();
        ToolCallback echo = recordingCallback("echo",
                "Returns the input verbatim — used as a stand-in for any MCP tool.",
                args -> {
                    seenArgs.set(args);
                    return "{\"ok\":true,\"received\":" + args + "}";
                });
        ToolDispatcher d = dispatcher(echo);

        String result = d.dispatch("echo", "{\"foo\":42}");

        assertThat(seenArgs.get()).isEqualTo("{\"foo\":42}");
        assertThat(result).isEqualTo("{\"ok\":true,\"received\":{\"foo\":42}}");
    }

    @Test
    void unknownToolThrowsWithAvailableNamesInMessage() {
        ToolDispatcher d = dispatcher(
                recordingCallback("alpha", "first", args -> "a"),
                recordingCallback("beta",  "second", args -> "b"));

        assertThatThrownBy(() -> d.dispatch("gamma", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown tool: gamma")
                // Sorted list of what IS available — helps the caller fix a typo.
                .hasMessageContaining("[alpha, beta]");
    }

    @Test
    void availableToolNamesIsAlphabeticallySorted() {
        ToolDispatcher d = dispatcher(
                recordingCallback("zeta", "z", args -> ""),
                recordingCallback("alpha", "a", args -> ""),
                recordingCallback("mu", "m", args -> ""));

        assertThat(d.availableToolNames()).containsExactly("alpha", "mu", "zeta");
    }

    @Test
    void emptyProviderProducesEmptyToolList() {
        ToolDispatcher d = dispatcher();
        assertThat(d.availableToolNames()).isEmpty();
    }

    @Test
    void mcpClientDisabledShowsClearMessageOnDispatch() {
        // ObjectProvider.getIfAvailable() returns null when the bean is
        // absent — that's the path when spring.ai.mcp.client.enabled=false
        // (the test-resources default for finance-agent).
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        ToolDispatcher d = new ToolDispatcher(empty);

        assertThat(d.availableToolNames()).isEmpty();
        assertThatThrownBy(() -> d.dispatch("anything", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP client is disabled")
                .hasMessageContaining("spring.ai.mcp.client.enabled=true");
    }

    /** Builds a minimal {@link ToolCallback} that delegates {@code call} to a function. */
    private static ToolCallback recordingCallback(String name, String description,
                                                  java.util.function.Function<String, String> body) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema("{}")
                .build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String input) { return body.apply(input); }
        };
    }

    /**
     * Wraps a {@link ToolCallbackProvider} into the {@link ObjectProvider}
     * shape {@link ToolDispatcher} expects. Production wiring uses Spring's
     * own ObjectProvider; here a one-method mock is enough.
     */
    private static ToolDispatcher dispatcher(ToolCallback... callbacks) {
        ToolCallbackProvider tcp = ToolCallbackProvider.from(callbacks);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(tcp);
        return new ToolDispatcher(op);
    }
}
