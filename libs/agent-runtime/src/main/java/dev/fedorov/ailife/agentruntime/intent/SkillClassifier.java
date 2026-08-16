package dev.fedorov.ailife.agentruntime.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * The shared in-agent routing classifier: turns a user message into one decision — invoke an MCP
 * tool, run one of the agent's own multi-step flows/skills, or just reply in chat.
 *
 * <p>Lifted out of the per-agent {@code IntentRouter}s (finance + tasks ran near-identical copies;
 * the tasks one was literally commented "Mirrors finance-agent's IntentRouter"). This class owns the
 * two duplicated halves — <b>prompt build</b> ({@link #buildPrompt}) and <b>strict-JSON parse</b>
 * with the lenient fallback ({@link #parse}) — while the agent keeps the parts that are genuinely its
 * own: the LLM round-trip, the {@link ToolSpec} list it can dispatch, and how each {@link Choice}
 * (flow/skill) is executed. SKILL.md descriptions remain the single source of truth for the trigger
 * phrasing (the agent passes them in via {@link Choice#promptBlock}).
 *
 * <p>Deliberately pure and side-effect-free (no {@code LlmClient}, no dispatcher) so it unit-tests as
 * plain string/JSON assertions and pulls no {@code spring-ai} into the shared library — the agent
 * maps its {@code ToolDefinition}s to {@link ToolSpec}s at the call site.
 */
public final class SkillClassifier {

    private final ObjectMapper json;

    public SkillClassifier(ObjectMapper json) {
        this.json = json;
    }

    /**
     * A tool the agent can dispatch, described for the classifier prompt. The agent maps each
     * {@code org.springframework.ai.tool.definition.ToolDefinition} to one of these at the call
     * site — {@code new ToolSpec(t.name(), t.description(), t.inputSchema())} — so the classifier
     * stays free of the MCP tool representation.
     */
    public record ToolSpec(String name, String description, String inputSchema) {
    }

    /**
     * An agent-supplied action beyond the built-in {@code tool}/{@code chat}: a finance flow
     * ({@code advice}/{@code report}/{@code invest}/{@code category}) or a tasks intent
     * {@code skill}. The prompt text is agent-owned so the classifier is domain-agnostic:
     * <ul>
     *   <li>{@code action} — the literal JSON {@code "action"} value that selects this choice on
     *   parse (e.g. {@code "advice"}, {@code "skill"}).</li>
     *   <li>{@code promptBlock} — the description block placed between the tool list and the JSON
     *   contract; sourced from the flow's SKILL.md {@code description} (the SSOT).</li>
     *   <li>{@code shapeExample} — the one-line JSON shape shown in the "Use ONE of these shapes"
     *   list (e.g. {@code {"action":"report","period":"month"}}).</li>
     * </ul>
     */
    public record Choice(String action, String promptBlock, String shapeExample) {
    }

    /** The classification outcome. */
    public sealed interface Decision permits ToolCall, FlowCall, Chat {
    }

    /**
     * Route to an MCP tool: dispatch {@code name} with {@code argsJson} (already stringified —
     * {@code "{}"} when the model omitted {@code args}). Covers both the canonical
     * {@code {"action":"tool","name":…}} shape and the flattened {@code {"action":"<toolName>",…}}
     * one smaller models emit.
     */
    public record ToolCall(String name, String argsJson) implements Decision {
    }

    /**
     * Route to one of the agent's registered {@link Choice}s. {@code action} names it; {@code node}
     * is the full parsed routing object so the agent's flow-map can read flow-specific fields it put
     * in the shape (e.g. {@code period}, {@code symbols}, the intent-skill {@code name}).
     */
    public record FlowCall(String action, JsonNode node) implements Decision {
    }

    /**
     * Reply directly to the user with {@code text}. Produced for {@code action=chat}, for an
     * unrecognised action, and for any body that isn't strict routing JSON (lenient fallback — a
     * chatty model that answers in prose never breaks the agent).
     */
    public record Chat(String text) implements Decision {
    }

    /**
     * Convenience overload with no agent-specific {@code extraRules} — see
     * {@link #buildPrompt(String, List, List, String, List)}.
     */
    public String buildPrompt(String intro, List<ToolSpec> tools, List<Choice> choices, String decidePrompt) {
        return buildPrompt(intro, tools, choices, decidePrompt, List.of());
    }

    /**
     * Build the classifier system prompt: {@code intro} + the tool list + each choice's prompt block
     * + {@code decidePrompt} + the strict-JSON output contract (the {@code tool} shape, each choice's
     * shape, the {@code chat} shape) + any agent-specific {@code extraRules} + the missing-argument
     * rule. Kept next to {@link #parse} because the two evolve together — the shapes advertised here
     * are exactly the ones parsed there.
     *
     * @param intro        the opening framing line(s) (agent-specific, e.g. "You can either reply
     *                     directly to the user or invoke one of these MCP tools.")
     * @param tools        the tools the agent can dispatch (may be empty)
     * @param choices      the agent's extra flow/skill actions (may be empty)
     * @param decidePrompt the "Decide: … or just talk?" question placed right before the contract
     * @param extraRules   agent-specific constraint paragraphs appended verbatim <b>after</b> the
     *                     shape list and <b>before</b> the shared missing-argument rule — the slot for
     *                     domain rules the shared scaffold can't own (e.g. finance's enum-pinning that
     *                     stops a 7B inventing action values). Each element is appended as-is, so the
     *                     caller controls its own trailing newline; blank/null entries are skipped.
     */
    public String buildPrompt(String intro, List<ToolSpec> tools, List<Choice> choices,
                              String decidePrompt, List<String> extraRules) {
        StringBuilder sb = new StringBuilder();
        sb.append(intro).append("\n\n");
        // A skills-only agent (no MCP tools) must not advertise the tool section or the tool shape —
        // it only confuses the model into emitting {"action":"tool",…} it can never dispatch (notes-agent,
        // the first skills-only consumer, mis-routed recall to chat until this was gated). finance/tasks
        // (tools present) get the identical prompt as before.
        boolean hasTools = tools != null && !tools.isEmpty();
        if (hasTools) {
            sb.append("Available tools:\n");
            for (ToolSpec t : tools) {
                sb.append("- ").append(t.name()).append(": ").append(t.description());
                String schema = t.inputSchema();
                if (schema != null && !schema.isBlank()) {
                    sb.append("\n  inputSchema: ").append(schema);
                }
                sb.append('\n');
            }
        }
        for (Choice c : choices) {
            if (c.promptBlock() != null && !c.promptBlock().isBlank()) {
                sb.append('\n').append(c.promptBlock()).append('\n');
            }
        }
        sb.append('\n');
        if (decidePrompt != null && !decidePrompt.isBlank()) {
            sb.append(decidePrompt).append("\n\n");
        }
        sb.append("Reply with strict JSON ONLY. No markdown fences, no commentary, no extra prose.\n");
        sb.append("Use ONE of these shapes:\n");
        if (hasTools) {
            sb.append("  {\"action\":\"tool\",\"name\":\"<tool-name>\",\"args\":{...}}\n");
        }
        for (Choice c : choices) {
            if (c.shapeExample() != null && !c.shapeExample().isBlank()) {
                sb.append("  ").append(c.shapeExample()).append('\n');
            }
        }
        sb.append("  {\"action\":\"chat\",\"text\":\"<reply to the user>\"}\n\n");
        if (extraRules != null) {
            for (String rule : extraRules) {
                if (rule != null && !rule.isBlank()) {
                    sb.append(rule);
                }
            }
        }
        if (hasTools) {
            sb.append("If the user's message lacks required arguments for a tool, use action=chat ");
            sb.append("and ask the user (in their language) for the missing piece — do NOT invent ");
            sb.append("arguments. ");
        }
        sb.append("If they're just chatting, use action=chat with a helpful reply.\n");
        return sb.toString();
    }

    /**
     * Parse a raw LLM classification response into a {@link Decision}.
     *
     * <p>Strict-JSON only: a body that doesn't start with {@code '{'} (or fails to parse, or isn't an
     * object, or has no {@code "action"}) is returned as {@link Chat} carrying the raw body — the
     * lenient fallback. A recognised {@code action}:
     * <ul>
     *   <li>{@code "tool"} → {@link ToolCall} from {@code name}/{@code args};</li>
     *   <li>a known tool <i>name</i> in the {@code action} field → {@link ToolCall} (the flattened
     *   shape smaller models emit);</li>
     *   <li>one of the supplied {@code choices} → {@link FlowCall} carrying the whole node;</li>
     *   <li>{@code "chat"} or anything else → {@link Chat} (prefer the {@code text} field, else raw).</li>
     * </ul>
     */
    public Decision parse(String raw, List<ToolSpec> tools, List<Choice> choices) {
        String body = raw == null ? "" : raw.strip();
        JsonNode node = tryParse(body);
        if (node == null || !node.isObject() || !node.hasNonNull("action")) {
            return new Chat(body);
        }
        String action = node.get("action").asString();
        if ("tool".equals(action)) {
            return toolCall(node.path("name").asString(), node);
        }
        if (isKnownTool(action, tools)) {
            // Format-drift tolerance (Stage 5 golden finding): smaller models flatten
            // {"action":"tool","name":"X"} to {"action":"X",…}. Accept it when action IS a tool name.
            return toolCall(action, node);
        }
        for (Choice c : choices) {
            if (c.action().equals(action)) {
                return new FlowCall(action, node);
            }
        }
        String text = node.has("text") ? node.get("text").asString() : body;
        return new Chat(text);
    }

    private ToolCall toolCall(String name, JsonNode node) {
        JsonNode argsNode = node.path("args");
        String argsJson = argsNode.isMissingNode() || argsNode.isNull() ? "{}" : argsNode.toString();
        return new ToolCall(name, argsJson);
    }

    private boolean isKnownTool(String action, List<ToolSpec> tools) {
        for (ToolSpec t : tools) {
            if (t.name().equals(action)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode tryParse(String body) {
        if (body.isEmpty() || body.charAt(0) != '{') {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }
}
