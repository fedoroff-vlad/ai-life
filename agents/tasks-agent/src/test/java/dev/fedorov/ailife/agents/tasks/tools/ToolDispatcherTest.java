package dev.fedorov.ailife.agents.tasks.tools;

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
 * Unit tests for {@link ToolDispatcher}. A hand-rolled {@link ToolCallbackProvider} stands in for
 * a live MCP server — the SSE round-trip is proven by {@code McpClientWiringTest}; this covers the
 * dispatcher's lookup + arg-passthrough + error contract. Mirrors finance's {@code ToolDispatcherTest}.
 */
class ToolDispatcherTest {

    @Test
    void dispatchInvokesMatchingCallbackAndReturnsItsResult() {
        AtomicReference<String> seenArgs = new AtomicReference<>();
        ToolCallback echo = recordingCallback("add_task",
                "Capture a task to the inbox.",
                args -> {
                    seenArgs.set(args);
                    return "{\"ok\":true,\"received\":" + args + "}";
                });
        ToolDispatcher d = dispatcher(echo);

        String result = d.dispatch("add_task", "{\"title\":\"milk\"}");

        assertThat(seenArgs.get()).isEqualTo("{\"title\":\"milk\"}");
        assertThat(result).isEqualTo("{\"ok\":true,\"received\":{\"title\":\"milk\"}}");
    }

    @Test
    void unknownToolThrowsWithAvailableNamesInMessage() {
        ToolDispatcher d = dispatcher(
                recordingCallback("add_task", "capture", args -> "a"),
                recordingCallback("list_tasks", "list", args -> "b"));

        assertThatThrownBy(() -> d.dispatch("ghost", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown tool: ghost")
                .hasMessageContaining("[add_task, list_tasks]");
    }

    @Test
    void availableToolNamesIsAlphabeticallySorted() {
        ToolDispatcher d = dispatcher(
                recordingCallback("list_tasks", "l", args -> ""),
                recordingCallback("add_task", "a", args -> ""),
                recordingCallback("clarify_task", "c", args -> ""));

        assertThat(d.availableToolNames()).containsExactly("add_task", "clarify_task", "list_tasks");
    }

    @Test
    void emptyProviderProducesEmptyToolList() {
        ToolDispatcher d = dispatcher();
        assertThat(d.availableToolNames()).isEmpty();
    }

    @Test
    void mcpClientDisabledShowsClearMessageOnDispatch() {
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

    private static ToolDispatcher dispatcher(ToolCallback... callbacks) {
        ToolCallbackProvider tcp = ToolCallbackProvider.from(callbacks);
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(tcp);
        return new ToolDispatcher(op);
    }
}
