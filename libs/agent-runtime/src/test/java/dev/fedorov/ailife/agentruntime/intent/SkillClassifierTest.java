package dev.fedorov.ailife.agentruntime.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.Choice;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.Chat;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.Decision;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.FlowCall;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.ToolCall;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.ToolSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared {@link SkillClassifier}. Pure input→output (no {@code LlmClient}, no
 * dispatcher), so every case is a plain string/JSON assertion. Pins down the two halves lifted out of
 * the per-agent {@code IntentRouter}s: the strict-JSON {@link SkillClassifier#parse} (with its
 * flattened-tool tolerance + lenient fallback) and the {@link SkillClassifier#buildPrompt} scaffold.
 */
class SkillClassifierTest {

    private final SkillClassifier classifier = new SkillClassifier(new ObjectMapper());

    private final List<ToolSpec> tools = List.of(
            new ToolSpec("add_transaction", "Record a spend.", "{\"type\":\"object\"}"),
            new ToolSpec("import_moneypro_csv", "Import Money Pro CSV.", "{\"type\":\"object\"}"));

    // Two representative agent choices: finance's action-is-the-selector shape ("report" with a field)
    // and tasks' one-action-many-skills shape ("skill" with a name).
    private final List<Choice> choices = List.of(
            new Choice("report", "REPORT flow: use it for a period summary document.",
                    "{\"action\":\"report\",\"period\":\"month\"}"),
            new Choice("skill", "SKILL flow: run a named multi-step skill.",
                    "{\"action\":\"skill\",\"name\":\"<skill-name>\"}"));

    // --- parse: tool ---

    @Test
    void parsesCanonicalToolShapeWithArgs() {
        Decision d = classifier.parse(
                "{\"action\":\"tool\",\"name\":\"import_moneypro_csv\","
                        + "\"args\":{\"dryRun\":true,\"householdId\":\"abc\"}}", tools, choices);

        assertThat(d).isInstanceOf(ToolCall.class);
        ToolCall t = (ToolCall) d;
        assertThat(t.name()).isEqualTo("import_moneypro_csv");
        assertThat(t.argsJson()).isEqualTo("{\"dryRun\":true,\"householdId\":\"abc\"}");
    }

    @Test
    void toolWithMissingArgsDefaultsToEmptyObject() {
        Decision d = classifier.parse(
                "{\"action\":\"tool\",\"name\":\"add_transaction\"}", tools, choices);

        assertThat(d).isInstanceOf(ToolCall.class);
        assertThat(((ToolCall) d).argsJson()).isEqualTo("{}");
    }

    @Test
    void acceptsFlattenedShapeWhenActionIsAKnownToolName() {
        // Smaller models flatten {"action":"tool","name":"X"} to {"action":"X",…}.
        Decision d = classifier.parse(
                "{\"action\":\"add_transaction\",\"args\":{\"amount\":5}}", tools, choices);

        assertThat(d).isInstanceOf(ToolCall.class);
        ToolCall t = (ToolCall) d;
        assertThat(t.name()).isEqualTo("add_transaction");
        assertThat(t.argsJson()).isEqualTo("{\"amount\":5}");
    }

    // --- parse: flow/skill choices ---

    @Test
    void routesRegisteredChoiceToFlowCallCarryingTheWholeNode() {
        Decision d = classifier.parse(
                "{\"action\":\"report\",\"period\":\"year\"}", tools, choices);

        assertThat(d).isInstanceOf(FlowCall.class);
        FlowCall f = (FlowCall) d;
        assertThat(f.action()).isEqualTo("report");
        // The agent's flow-map reads its own fields off the node.
        assertThat(f.node().path("period").asText()).isEqualTo("year");
    }

    @Test
    void flowCallNodeExposesArbitraryAgentFields() {
        Decision d = classifier.parse(
                "{\"action\":\"skill\",\"name\":\"inbox-clarify\"}", tools, choices);

        assertThat(d).isInstanceOf(FlowCall.class);
        FlowCall f = (FlowCall) d;
        assertThat(f.action()).isEqualTo("skill");
        assertThat(f.node().path("name").asText()).isEqualTo("inbox-clarify");
    }

    // --- parse: chat + lenient fallback ---

    @Test
    void routesChatActionToChatPreferringTheTextField() {
        Decision d = classifier.parse(
                "{\"action\":\"chat\",\"text\":\"Прикрепи CSV-файл.\"}", tools, choices);

        assertThat(d).isInstanceOf(Chat.class);
        assertThat(((Chat) d).text()).isEqualTo("Прикрепи CSV-файл.");
    }

    @Test
    void unrecognisedActionFallsBackToChatWithRawBodyWhenNoTextField() {
        // Not "tool", not a known tool name, not a registered choice, no "text" → the raw body.
        String raw = "{\"action\":\"analysis\"}";
        Decision d = classifier.parse(raw, tools, choices);

        assertThat(d).isInstanceOf(Chat.class);
        assertThat(((Chat) d).text()).isEqualTo(raw);
    }

    @Test
    void nonJsonBodyIsTreatedAsRawChatReply() {
        Decision d = classifier.parse("Извини, не понял запрос.", tools, choices);

        assertThat(d).isInstanceOf(Chat.class);
        assertThat(((Chat) d).text()).isEqualTo("Извини, не понял запрос.");
    }

    @Test
    void jsonObjectWithoutActionIsTreatedAsChat() {
        Decision d = classifier.parse("{\"foo\":\"bar\"}", tools, choices);

        assertThat(d).isInstanceOf(Chat.class);
        assertThat(((Chat) d).text()).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void blankBodyIsAnEmptyChat() {
        assertThat(classifier.parse(null, tools, choices)).isEqualTo(new Chat(""));
        assertThat(classifier.parse("   ", tools, choices)).isEqualTo(new Chat(""));
    }

    @Test
    void leadingWhitespaceBeforeJsonStillParses() {
        Decision d = classifier.parse(
                "\n  {\"action\":\"tool\",\"name\":\"add_transaction\",\"args\":{}}", tools, choices);

        assertThat(d).isInstanceOf(ToolCall.class);
        assertThat(((ToolCall) d).name()).isEqualTo("add_transaction");
    }

    // --- buildPrompt ---

    @Test
    void buildPromptContainsIntroToolsChoicesDecideAndTheJsonContract() {
        String prompt = classifier.buildPrompt(
                "You can reply directly or invoke a tool.", tools, choices,
                "Decide: run a tool, run a flow, or just talk?");

        // Intro + tool list (with schema) + choice blocks + decide line.
        assertThat(prompt).startsWith("You can reply directly or invoke a tool.\n\n");
        assertThat(prompt).contains("Available tools:\n");
        assertThat(prompt).contains("- add_transaction: Record a spend.");
        assertThat(prompt).contains("\n  inputSchema: {\"type\":\"object\"}");
        assertThat(prompt).contains("REPORT flow: use it for a period summary document.");
        assertThat(prompt).contains("Decide: run a tool, run a flow, or just talk?");
        // The contract advertises exactly the shapes parse() accepts: tool + each choice + chat.
        assertThat(prompt).contains("  {\"action\":\"tool\",\"name\":\"<tool-name>\",\"args\":{...}}");
        assertThat(prompt).contains("  {\"action\":\"report\",\"period\":\"month\"}");
        assertThat(prompt).contains("  {\"action\":\"skill\",\"name\":\"<skill-name>\"}");
        assertThat(prompt).contains("  {\"action\":\"chat\",\"text\":\"<reply to the user>\"}");
        assertThat(prompt).contains("lacks required arguments for a tool");
    }

    @Test
    void buildPromptPlacesExtraRulesAfterTheShapesAndBeforeTheMissingArgumentRule() {
        String enumPinning = "The \"action\" value MUST be exactly one of these literal strings.\n";
        String prompt = classifier.buildPrompt(
                "Intro.", tools, choices, "Decide?", List.of(enumPinning));

        assertThat(prompt).contains(enumPinning);
        // Position contract: extra rule sits between the chat shape and the shared missing-argument
        // rule (the slot finance's enum-pinning occupied before it lifted onto this classifier).
        int chatShape = prompt.indexOf("{\"action\":\"chat\",\"text\":\"<reply to the user>\"}");
        int extra = prompt.indexOf(enumPinning);
        int missingArg = prompt.indexOf("lacks required arguments for a tool");
        assertThat(chatShape).isLessThan(extra);
        assertThat(extra).isLessThan(missingArg);
    }

    @Test
    void buildPromptWithNoToolsOrChoicesStillHasToolAndChatShapes() {
        String prompt = classifier.buildPrompt("Intro.", List.of(), List.of(), null);

        assertThat(prompt).contains("Available tools:\n");
        assertThat(prompt).contains("  {\"action\":\"tool\",\"name\":\"<tool-name>\",\"args\":{...}}");
        assertThat(prompt).contains("  {\"action\":\"chat\",\"text\":\"<reply to the user>\"}");
        // No choices → no extra shape lines between tool and chat.
        assertThat(prompt).doesNotContain("\"action\":\"report\"");
        assertThat(prompt).doesNotContain("\"action\":\"skill\"");
    }
}
