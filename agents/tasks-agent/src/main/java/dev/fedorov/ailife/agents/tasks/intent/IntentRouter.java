package dev.fedorov.ailife.agents.tasks.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.tasks.tools.ToolDispatcher;
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
 * Routes a user message into either an mcp-tasks tool call or a plain LLM reply. Mirrors
 * finance-agent's {@code IntentRouter} (PR35).
 *
 * <p>Pipeline (single LLM round-trip):
 * <ol>
 *   <li>If {@link ToolDispatcher#availableToolDefinitions()} is empty (MCP client disabled or no
 *   servers wired) skip routing and fall back to plain chat — back-compat with the skeleton.</li>
 *   <li>Otherwise prompt the LLM with AGENT.md body + a system message listing the available tools
 *   (name + description + input schema) and the strict JSON output contract.</li>
 *   <li>Parse: {@code action:tool} → dispatch via {@link ToolDispatcher}; {@code action:chat} →
 *   return its {@code text}; un-parseable → treat the raw body as the chat reply (lenient).</li>
 * </ol>
 *
 * <p>Tool-dispatch failures are caught and surfaced as a short Russian-locale error naming the
 * tool — no retry/re-route here (that's the orchestrator's concern).
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
                return Mono.just(new RouterResult(raw, null, model));
            }
            String action = node.get("action").asText();
            if ("tool".equals(action)) {
                return invokeTool(node, model);
            }
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
     * Outcome of a single {@link #route} call: {@code text} for the orchestrator to show,
     * {@code invokedTool} (non-null when a tool ran — informational), {@code llmModel} from the
     * routing turn (preserves the {@code IntentResponse.llmModel} contract).
     */
    public record RouterResult(String text, String invokedTool, String llmModel) {
    }
}
