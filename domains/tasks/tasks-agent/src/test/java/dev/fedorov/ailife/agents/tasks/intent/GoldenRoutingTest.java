package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.tasks.tools.ToolDispatcher;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises tasks-agent's intent routing against a <b>real model</b>
 * (local Ollama via a running llm-gateway), asserting <b>structure, not text</b> (roadmap §Risks). The
 * tasks sibling of finance's {@code GoldenRoutingTest}, added with the sharing retrofit (ADR-0002 slice 5)
 * because that slice made routing load-bearing: a plain capture must reach the {@code task-capture} flow
 * (the sharing write path — not the {@code add_task} tool, which can't route personal/shared), and a
 * "family tasks" ask must carry {@code scope:"shared"} (the read cut, slice 5b).
 *
 * <p><b>Opt-in / gated.</b> Skipped unless {@code GOLDEN_LLM} is set (CI default = unset). To run it (or
 * use {@code scripts/golden.sh -pl domains/tasks/tasks-agent -Dtest=GoldenRoutingTest}):
 * <pre>
 *   LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
 *   LLM_DEFAULT_MODEL=qwen3:8b LLM_GATEWAY_PORT=8081 \
 *     mvn -q -pl platform/llm-gateway spring-boot:run
 *   GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
 *     mvn -q -pl domains/tasks/tasks-agent -Dtest=GoldenRoutingTest test
 * </pre>
 *
 * <p>{@link ToolDispatcher} is mocked to expose the canonical mcp-tasks tool set. The intent-skill flows
 * are NOT mocked — the tasks router only <b>classifies</b> (it returns the chosen skill in
 * {@code RouterResult.invokedSkill}; {@code IntentController} runs the flow), so we assert which branch the
 * real model selected, never a flow's output.
 */
@GoldenLlmTest
class GoldenRoutingTest {

    /** The contract actions the tasks classifier prompt allows. */
    private static final Set<String> ACTIONS = Set.of("tool", "skill", "chat");

    /** The intent (user-invoked) skills the classifier can route to. */
    private static final Set<String> SKILLS =
            Set.of("inbox-clarify", "next-action-suggester", "task-capture", "task-delete", "task-edit",
                    "task-status");

    /** The mcp-tasks tools the dispatcher exposes (must match the canonical tool set). */
    private static final List<ToolDefinition> TOOLS = List.of(
            tool("upsert_project", "Create or update a GTD project."),
            tool("list_projects", "List GTD projects in a household."),
            tool("add_task", "Capture a task to the GTD inbox."),
            tool("list_tasks", "List tasks in a household with optional filters."),
            tool("clarify_task", "Clarify an inbox task into an organized GTD state."),
            tool("update_task", "Partial content edit of a task."),
            tool("complete_task", "Mark a task done."),
            tool("delete_task", "Delete a task and return the deleted row."),
            tool("link_task_to_event", "Link a task to a calendar event."),
            tool("enable_weekly_review", "Enable the GTD weekly review for a household."),
            tool("disable_weekly_review", "Disable the GTD weekly review for a household."));

    private static final Set<String> TOOL_NAMES =
            TOOLS.stream().map(ToolDefinition::name).collect(Collectors.toSet());

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final AgentManifest manifest = new AgentManifest(
            "tasks", "tasks agent", "0.1.0", 8096,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenRoutingTest.class.getClassLoader()));
    // Real tasks skills from the classpath (copied by the module pom from ../skills) so the router sources
    // each flow's trigger phrasing from the actual SKILL.md descriptions — the SSOT the behaviour test
    // depends on. weekly-review is loaded too but the router filters it out (it has triggers → not an
    // intent skill), mirroring production.
    private final SkillRegistry skills = loadTasksSkills();
    private final SkillClassifier classifier = new SkillClassifier(json);
    private final IntentRouter router = new IntentRouter(llm, dispatcher, manifest, skills, classifier);

    private static SkillRegistry loadTasksSkills() {
        ClassLoader cl = GoldenRoutingTest.class.getClassLoader();
        List<Skill> loaded = new java.util.ArrayList<>();
        for (String name : List.of("weekly-review", "inbox-clarify", "next-action-suggester",
                "task-capture", "task-delete", "task-edit", "task-status")) {
            loaded.add(GoldenLlm.skill(cl, "skills/tasks/" + name + "/SKILL.md"));
        }
        return new SkillRegistry(loaded);
    }

    GoldenRoutingTest() {
        when(dispatcher.availableToolDefinitions()).thenReturn(TOOLS);
        // A tool dispatch returns the tool's JSON result; stub a non-null body so the tool branch emits a
        // RouterResult (we assert the routing decision, not the tool's real output).
        when(dispatcher.dispatch(anyString(), anyString())).thenReturn("{\"ok\":true}");
    }

    /**
     * STRUCTURE — the real model, given the real prompts, must return well-formed routing JSON: a JSON
     * object with an {@code action} in the contract set, a real tool {@code name} when {@code action=tool},
     * and a real skill {@code name} when {@code action=skill}. "Structure, not text" — never checks wording.
     */
    @Test
    void classifierEmitsWellFormedRoutingJson() {
        String prompt = router.buildClassifierPrompt(TOOLS);
        for (String msg : List.of(
                "напомни купить молоко",
                "разбери мой инбокс",
                "что мне сейчас сделать",
                "какие у нас общие семейные дела на сегодня",
                "отметь задачу про молоко выполненной",
                "привет, как дела?")) {
            String raw = chat(prompt, msg);
            JsonNode node = extractJson(raw);
            if (node == null) {
                fail("Not parseable JSON for «%s» — raw model output was:\n%s".formatted(msg, raw));
            }
            assertThat(node.hasNonNull("action"))
                    .as("missing 'action' for «%s»: %s", msg, raw).isTrue();
            String action = node.get("action").asString();
            boolean controlAction = ACTIONS.contains(action);
            boolean flattenedTool = TOOL_NAMES.contains(action);
            assertThat(controlAction || flattenedTool)
                    .as("action '%s' is neither a control action nor a known tool, for «%s»: %s", action, msg, raw)
                    .isTrue();
            if ("tool".equals(action)) {
                assertThat(node.hasNonNull("name"))
                        .as("action=tool without 'name' for «%s»: %s", msg, raw).isTrue();
                assertThat(TOOL_NAMES)
                        .as("hallucinated tool '%s' for «%s»", node.get("name").asString(), msg)
                        .contains(node.get("name").asString());
            }
            if ("skill".equals(action)) {
                assertThat(SKILLS)
                        .as("hallucinated skill '%s' for «%s»", node.path("name").asString(), msg)
                        .contains(node.path("name").asString());
            }
        }
    }

    /**
     * BEHAVIOUR — unambiguous requests must reach the right branch end-to-end through {@link
     * IntentRouter#route}. A softer signal than the structure test (a small model can mis-route a
     * borderline phrasing), so the cases here are deliberately crisp.
     */
    @Test
    void routesUnambiguousRequestsToTheRightBranch() {
        // Warm-up (not asserted): the classifier system prompt is large (AGENT.md + the full tool list +
        // skill block), so its FIRST prefill on a CPU-only box can exceed the per-call block below. Ollama
        // caches the prompt PREFIX after one call, so this primes it; every asserted call then reuses the
        // cached prefix and fits the strict per-call SLA.
        router.route("привет").block(Duration.ofSeconds(180));

        // A plain capture routes to the task-capture flow (the sharing write path), NOT the add_task tool.
        assertRoutesToSkill("напомни купить молоко", "task-capture", false);
        assertRoutesToSkill("разбери мой инбокс", "inbox-clarify", false);
        // Own next-actions (default cut).
        assertRoutesToSkill("что мне сейчас сделать", "next-action-suggester", false);
        // Family/shared next-actions carry scope:"shared" (the read cut, slice 5b).
        assertRoutesToSkill("какие у нас общие семейные дела на сегодня", "next-action-suggester", true);
        // A delete-by-description routes to the task-delete flow, not the delete_task tool (#486/Track H.2).
        assertRoutesToSkill("удали задачу про молоко", "task-delete", false);
        // A rename routes to the task-edit flow, not the update_task tool (#486/Track H.2).
        assertRoutesToSkill("переименуй задачу про молоко в купить овсяное молоко", "task-edit", false);
        // A GTD state move routes to the task-status flow, not the clarify_task tool (#486/Track H.2).
        assertRoutesToSkill("переведи задачу про врача в ожидание", "task-status", false);
    }

    private void assertRoutesToSkill(String text, String expectedSkill, boolean expectedShared) {
        IntentRouter.RouterResult r = router.route(text).block(Duration.ofSeconds(60));
        assertThat(r).as("null result for «%s»", text).isNotNull();
        assertThat(r.invokedSkill())
                .as("«%s» should route to skill '%s' but invokedSkill='%s', invokedTool='%s' (text: %s)",
                        text, expectedSkill, r.invokedSkill(), r.invokedTool(), r.text())
                .isEqualTo(expectedSkill);
        if (expectedShared) {
            assertThat(r.shared())
                    .as("«%s» should carry scope:shared", text).isTrue();
        }
    }

    /** One real round-trip through the live model with the exact router prompt shape. */
    private String chat(String classifierPrompt, String userText) {
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(classifierPrompt),
                LlmMessage.user(userText)));
        LlmChatResponse resp = llm.chat(req).block(Duration.ofSeconds(90));
        assertThat(resp).as("no LLM response for «%s» — is llm-gateway up at %s?", userText, GoldenLlm.gatewayUrl()).isNotNull();
        return resp.content() == null ? "" : resp.content();
    }

    /** Lenient extraction: tolerate ```json fences / leading prose, parse the first JSON object found. */
    private JsonNode extractJson(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode n = json.readTree(raw.substring(start, end + 1));
            return n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ToolDefinition tool(String name, String description) {
        return DefaultToolDefinition.builder()
                .name(name).description(description)
                .inputSchema("{\"type\":\"object\"}").build();
    }
}
