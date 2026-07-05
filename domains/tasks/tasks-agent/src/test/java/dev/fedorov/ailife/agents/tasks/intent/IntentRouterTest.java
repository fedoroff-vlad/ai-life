package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.tools.ToolDispatcher;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IntentRouter} — mock {@link LlmClient} + {@link ToolDispatcher} exercise
 * the branches without an external service. Mirrors finance's {@code IntentRouterTest}.
 */
class IntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "tasks", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the tasks agent for the ai-life system.");

    private final SkillRegistry skills = new SkillRegistry(List.of());
    private final IntentRouter router = new IntentRouter(llm, dispatcher, manifest, skills, json);

    @Test
    void noToolsWiredFallsBackToPlainChatNoRoutingPrompt() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of());
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("mock-large", "Конечно, помогу."));
        });

        StepVerifier.create(router.route("Привет"))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("Конечно, помогу.");
                    assertThat(r.invokedTool()).isNull();
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        // Fallback uses ONLY AGENT.md body + user text — no classifier prompt.
        assertThat(seen.get().messages()).hasSize(2);
        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmPicksToolAndDispatcherIsInvokedWithExactArgsJson() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("add_task",
                "Capture a task to the inbox.")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"tool\",\"name\":\"add_task\","
                        + "\"args\":{\"householdId\":\"abc\",\"title\":\"молоко\"}}")));
        when(dispatcher.dispatch(eq("add_task"),
                eq("{\"householdId\":\"abc\",\"title\":\"молоко\"}")))
                .thenReturn("{\"id\":\"x\",\"status\":\"inbox\"}");

        StepVerifier.create(router.route("напомни купить молоко"))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("{\"id\":\"x\",\"status\":\"inbox\"}");
                    assertThat(r.invokedTool()).isEqualTo("add_task");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();
    }

    @Test
    void llmPicksChatAndTextFieldPropagatesVerbatim() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("add_task", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"chat\",\"text\":\"Что именно записать в задачи?\"}")));

        StepVerifier.create(router.route("эээ"))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("Что именно записать в задачи?");
                    assertThat(r.invokedTool()).isNull();
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmReturnsNonJsonTreatsRawBodyAsChatReply() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("add_task", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "Извини, не понял запрос.")));

        StepVerifier.create(router.route("???"))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("Извини, не понял запрос.");
                    assertThat(r.invokedTool()).isNull();
                })
                .verifyComplete();

        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void toolDispatchFailureSurfacesAsUserFacingErrorWithoutThrowing() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("add_task", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"tool\",\"name\":\"add_task\",\"args\":{}}")));
        doThrow(new IllegalArgumentException("Missing required field: title"))
                .when(dispatcher).dispatch(eq("add_task"), eq("{}"));

        StepVerifier.create(router.route("запиши"))
                .assertNext(r -> {
                    assertThat(r.text()).contains("add_task");
                    assertThat(r.text()).contains("Missing required field");
                    assertThat(r.invokedTool()).isEqualTo("add_task");
                })
                .verifyComplete();
    }

    @Test
    void llmRoutesToIntentSkillWhenUserAsksForIt() {
        Skill inboxClarify = new Skill("inbox-clarify", "Propose a GTD clarification for inbox items.",
                "0.1.0", "tasks", List.of(), List.of("en", "ru"), "skill body");
        IntentRouter skillRouter = new IntentRouter(llm, dispatcher, manifest,
                new SkillRegistry(List.of(inboxClarify)), json);
        // No MCP tools wired, but the intent skill alone is enough to build the classifier.
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of());
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("mock-large", "{\"action\":\"skill\",\"name\":\"inbox-clarify\"}"));
        });

        StepVerifier.create(skillRouter.route("разбери мой инбокс"))
                .assertNext(r -> {
                    assertThat(r.invokedSkill()).isEqualTo("inbox-clarify");
                    assertThat(r.text()).isNull();
                    assertThat(r.invokedTool()).isNull();
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        // The classifier prompt advertised the skill so the LLM could pick it.
        assertThat(seen.get().messages()).hasSize(3);
        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmRoutesToUnknownSkillFallsBackToChat() {
        Skill inboxClarify = new Skill("inbox-clarify", "x", "0.1.0", "tasks",
                List.of(), List.of("en"), "body");
        IntentRouter skillRouter = new IntentRouter(llm, dispatcher, manifest,
                new SkillRegistry(List.of(inboxClarify)), json);
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of());
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"skill\",\"name\":\"ghost-skill\"}")));

        StepVerifier.create(skillRouter.route("что-то"))
                .assertNext(r -> {
                    assertThat(r.invokedSkill()).isNull();
                    assertThat(r.invokedTool()).isNull();
                    // Unknown skill → the raw body is treated as the chat reply.
                    assertThat(r.text()).contains("ghost-skill");
                })
                .verifyComplete();
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }

    private static ToolDefinition toolDef(String name, String description) {
        return DefaultToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema("{\"type\":\"object\"}")
                .build();
    }
}
