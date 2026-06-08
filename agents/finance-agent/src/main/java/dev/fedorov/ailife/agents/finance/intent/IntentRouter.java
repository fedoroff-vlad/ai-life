package dev.fedorov.ailife.agents.finance.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.tools.ToolDispatcher;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Routes a user message into either an MCP tool call or a plain LLM reply.
 *
 * <p>Pipeline (single LLM round-trip):
 * <ol>
 *   <li>If {@link ToolDispatcher#availableToolDefinitions()} is empty (MCP
 *   client disabled or no servers wired), skip routing entirely and fall back
 *   to the plain chat path — back-compat with the pre-PR35 behaviour.</li>
 *   <li>Otherwise prompt the LLM with: AGENT.md body + a system message
 *   listing the available tools (name + description + input schema) and the
 *   required JSON output shape ({@code action: tool|chat}, plus
 *   {@code name + args} or {@code text}).</li>
 *   <li>Parse the LLM response. If it's well-formed JSON with
 *   {@code action: tool}, dispatch via {@link ToolDispatcher} and return the
 *   tool result; if {@code action: chat}, return its {@code text}; if the
 *   response can't be parsed (no opening brace, missing fields, …) we treat
 *   the raw response as the plain chat answer — lenient parsing keeps a
 *   chatty model from breaking the agent.</li>
 * </ol>
 *
 * <p>Tool-dispatch failures (unknown name, invalid args, MCP server down) are
 * caught and surfaced to the user as a short Russian-locale error string with
 * the tool name — the LLM might re-route on a follow-up, or the user can
 * adjust their request. We deliberately do NOT retry or re-route here;
 * multi-turn recovery is the orchestrator's concern.
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private final LlmClient llm;
    private final ToolDispatcher dispatcher;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public IntentRouter(LlmClient llm,
                        ToolDispatcher dispatcher,
                        AgentManifest manifest,
                        ObjectMapper json) {
        this.llm = llm;
        this.dispatcher = dispatcher;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<RouterResult> route(String userText) {
        List<ToolDefinition> tools = dispatcher.availableToolDefinitions();
        if (tools.isEmpty()) {
            // No MCP tools wired — straight chat, no routing prompt
            // overhead, no JSON-parse surprises.
            return chatOnly(userText);
        }

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(buildClassifierPrompt(tools)),
                LlmMessage.user(userText)));

        return llm.chat(req).flatMap(resp -> {
            String raw = resp.content() == null ? "" : resp.content().trim();
            String model = resp.model();
            JsonNode node = tryParse(raw);
            if (node == null || !node.isObject() || !node.hasNonNull("action")) {
                // Not a routing JSON — treat as a plain chat reply.
                return Mono.just(new RouterResult(raw, null, model));
            }
            String action = node.get("action").asText();
            if ("tool".equals(action)) {
                return invokeTool(node, model);
            }
            // action=chat (or anything else we don't recognise): prefer the
            // structured 'text' field, else fall back to the raw body.
            String text = node.has("text") ? node.get("text").asText() : raw;
            return Mono.just(new RouterResult(text, null, model));
        });
    }

    private Mono<RouterResult> chatOnly(String userText) {
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(userText)));
        return llm.chat(req).map(r -> new RouterResult(
                r.content() == null ? "" : r.content(), null, r.model()));
    }

    private Mono<RouterResult> invokeTool(JsonNode node, String llmModel) {
        String name = node.path("name").asText();
        JsonNode argsNode = node.path("args");
        String argsJson = argsNode.isMissingNode() || argsNode.isNull() ? "{}" : argsNode.toString();
        // ToolCallback.call is blocking (SSE under the hood) — same handling
        // as InternalToolsController.
        return Mono.fromCallable(() -> dispatcher.dispatch(name, argsJson))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> new RouterResult(result, name, llmModel))
                .onErrorResume(e -> {
                    log.warn("tool dispatch failed for {}: {}", name, e.toString());
                    return Mono.just(new RouterResult(
                            "Не удалось вызвать инструмент «" + name + "»: " + e.getMessage(),
                            name, llmModel));
                });
    }

    private JsonNode tryParse(String raw) {
        if (raw.isEmpty() || raw.charAt(0) != '{') return null;
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build the system prompt that tells the LLM about the available tools
     * and the strict JSON output contract. Kept inline (not externalised to a
     * template file) because it's tightly coupled to the parsing on the Java
     * side — the two evolve together.
     */
    private String buildClassifierPrompt(List<ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You can either reply directly to the user or invoke one of these MCP tools.\n\n");
        sb.append("Available tools:\n");
        for (ToolDefinition t : tools) {
            sb.append("- ").append(t.name()).append(": ").append(t.description());
            String schema = t.inputSchema();
            if (schema != null && !schema.isBlank()) {
                sb.append("\n  inputSchema: ").append(schema);
            }
            sb.append('\n');
        }
        sb.append('\n');
        sb.append("Decide: does the user want to run a tool, or just talk?\n\n");
        sb.append("Reply with strict JSON ONLY. No markdown fences, no commentary, no extra prose.\n");
        sb.append("Use ONE of these two shapes:\n");
        sb.append("  {\"action\":\"tool\",\"name\":\"<tool-name>\",\"args\":{...}}\n");
        sb.append("  {\"action\":\"chat\",\"text\":\"<reply to the user>\"}\n\n");
        sb.append("If the user's message lacks required arguments for a tool, use action=chat ");
        sb.append("and ask the user (in their language) for the missing piece — do NOT invent ");
        sb.append("arguments. If they're just chatting, use action=chat with a helpful reply.\n");
        return sb.toString();
    }

    /**
     * Outcome of a single {@link #route} call.
     *
     * <ul>
     *   <li>{@code text} — what the orchestrator should show the user.</li>
     *   <li>{@code invokedTool} — non-null when the result came from a tool
     *   dispatch (currently informational — future PR may use it for audit /
     *   analytics, or to drive a "do you want me to apply this?" follow-up
     *   turn).</li>
     *   <li>{@code llmModel} — model id from the LLM round-trip (the routing
     *   turn). Preserves the pre-PR35 {@code IntentResponse.llmModel} contract
     *   the orchestrator's intent tests already assert on.</li>
     * </ul>
     */
    public record RouterResult(String text, String invokedTool, String llmModel) {
    }
}
