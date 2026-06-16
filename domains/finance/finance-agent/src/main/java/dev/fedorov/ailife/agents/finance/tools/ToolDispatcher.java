package dev.fedorov.ailife.agents.finance.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Looks up a {@link ToolCallback} by name in the Spring AI MCP-client
 * {@link ToolCallbackProvider} and invokes it with the caller-supplied JSON
 * arguments. Pure dispatcher — no LLM, no classification, no auth: it's the
 * single seam between "I know which MCP tool I want to call" and "actually
 * call it over the configured transport (SSE in our case)".
 *
 * <p>This is the consumer-side of PR33's wiring. Today the only caller is
 * the system-level {@code POST /agents/finance/internal/tools/{name}}
 * endpoint; future user-facing intent flow (e.g. "импортируй CSV из Money
 * Pro") will reuse the dispatcher after an LLM picks the tool name + args.
 *
 * <p>The {@link ToolCallbackProvider} dependency is resolved via
 * {@link ObjectProvider} so the bean is still wired when the MCP client is
 * disabled ({@code spring.ai.mcp.client.enabled=false}, the default for
 * tests). In that mode {@link #availableToolNames()} returns an empty list
 * and {@link #dispatch} throws a clear "MCP client disabled" message.
 */
@Component
public class ToolDispatcher {

    private final ObjectProvider<ToolCallbackProvider> providerRef;

    public ToolDispatcher(ObjectProvider<ToolCallbackProvider> providerRef) {
        this.providerRef = providerRef;
    }

    /**
     * Invoke the tool whose {@link org.springframework.ai.tool.definition.ToolDefinition#name() name}
     * matches {@code toolName}. {@code jsonArgs} is the JSON-stringified
     * arguments object — Spring AI's {@code call(String)} contract; the
     * dispatcher does not deserialise or validate args, that's the tool's job.
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
     * Full tool definitions (name + description + JSON Schema) — used by
     * {@code IntentRouter} to build the LLM classifier prompt. Returns
     * empty when the MCP client is disabled. Order matches
     * {@link #availableToolNames()} (alphabetical) so the prompt stays
     * deterministic across restarts.
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
