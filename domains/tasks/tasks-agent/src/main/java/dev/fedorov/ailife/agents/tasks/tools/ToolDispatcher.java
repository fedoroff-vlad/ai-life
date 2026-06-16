package dev.fedorov.ailife.agents.tasks.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Looks up a {@link ToolCallback} by name in the Spring AI MCP-client {@link ToolCallbackProvider}
 * and invokes it with the caller-supplied JSON arguments. Pure dispatcher — no LLM, no
 * classification, no auth: the single seam between "I know which mcp-tasks tool I want" and
 * "actually call it over SSE". Consumer-side of PR56's wiring; mirrors finance-agent's
 * {@code ToolDispatcher} (PR34).
 *
 * <p>The {@link ToolCallbackProvider} is resolved via {@link ObjectProvider} so the bean stays
 * valid when the MCP client is disabled ({@code spring.ai.mcp.client.enabled=false}, the test
 * default). In that mode {@link #availableToolNames()} is empty and {@link #dispatch} throws a
 * clear "MCP client disabled" message rather than NPE'ing.
 */
@Component
public class ToolDispatcher {

    private final ObjectProvider<ToolCallbackProvider> providerRef;

    public ToolDispatcher(ObjectProvider<ToolCallbackProvider> providerRef) {
        this.providerRef = providerRef;
    }

    /**
     * Invoke the tool whose {@link ToolDefinition#name() name} matches {@code toolName}.
     * {@code jsonArgs} is the JSON-stringified arguments object (Spring AI's {@code call(String)}
     * contract); the dispatcher does not deserialise or validate args — that's the tool's job.
     *
     * @return the tool's JSON-stringified result.
     * @throws IllegalArgumentException if no tool matches {@code toolName}.
     */
    public String dispatch(String toolName, String jsonArgs) {
        ToolCallbackProvider p = providerRef.getIfAvailable();
        if (p == null) {
            throw new IllegalArgumentException("MCP client is disabled — no tools wired. "
                    + "Set spring.ai.mcp.client.enabled=true to enable the SSE transport.");
        }
        ToolCallback callback = find(p, toolName).orElseThrow(() ->
                new IllegalArgumentException("Unknown tool: " + toolName
                        + ". Known: " + availableToolNames()));
        return callback.call(jsonArgs);
    }

    /** Convenience for listing what's wired in — used by the introspection endpoint. */
    public List<String> availableToolNames() {
        ToolCallbackProvider p = providerRef.getIfAvailable();
        if (p == null) return List.of();
        return Arrays.stream(p.getToolCallbacks())
                .map(cb -> cb.getToolDefinition().name())
                .sorted()
                .toList();
    }

    /**
     * Full tool definitions (name + description + JSON Schema) — used by the future
     * {@code IntentRouter} (PR58) to build the LLM classifier prompt. Empty when the MCP client
     * is disabled; alphabetical order keeps the prompt deterministic across restarts.
     */
    public List<ToolDefinition> availableToolDefinitions() {
        ToolCallbackProvider p = providerRef.getIfAvailable();
        if (p == null) return List.of();
        return Arrays.stream(p.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    private Optional<ToolCallback> find(ToolCallbackProvider p, String toolName) {
        return Arrays.stream(p.getToolCallbacks())
                .filter(cb -> toolName.equals(cb.getToolDefinition().name()))
                .findFirst();
    }
}
