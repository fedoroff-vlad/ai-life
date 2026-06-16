package dev.fedorov.ailife.agents.finance.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.finance.tools.ToolDispatcher;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
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
 * Unit tests for {@link IntentRouter}. We feed mock {@link LlmClient} and
 * {@link ToolDispatcher} responses so the router's branches are exercised
 * without an external service. The five tests pin down:
 * <ul>
 *   <li>No tools wired → chat-only fallback (back-compat with pre-PR35).</li>
 *   <li>LLM picks a tool → dispatcher invoked with the exact args JSON.</li>
 *   <li>LLM picks chat → text reply propagates verbatim.</li>
 *   <li>LLM returns non-JSON → treated as raw chat reply (lenient parsing).</li>
 *   <li>Tool dispatch failure → user-facing Russian-locale error, not
 *   propagated as an exception.</li>
 * </ul>
 */
class IntentRouterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "finance", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the finance agent for the ai-life system.");

    private final IntentRouter router = new IntentRouter(llm, dispatcher, manifest, json);

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

        // The fallback path uses ONLY two messages: AGENT.md body + user
        // text. No routing/classifier prompt — confirms tools=empty
        // short-circuits before that branch.
        assertThat(seen.get().messages()).hasSize(2);
        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmPicksToolAndDispatcherIsInvokedWithExactArgsJson() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("import_moneypro_csv",
                "Import Money Pro CSV history.")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"tool\",\"name\":\"import_moneypro_csv\","
                        + "\"args\":{\"dryRun\":true,\"householdId\":\"abc\"}}")));
        when(dispatcher.dispatch(eq("import_moneypro_csv"),
                eq("{\"dryRun\":true,\"householdId\":\"abc\"}")))
                .thenReturn("{\"created\":0,\"skipped\":0}");

        StepVerifier.create(router.route("импортируй CSV из Money Pro, dry-run"))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("{\"created\":0,\"skipped\":0}");
                    assertThat(r.invokedTool()).isEqualTo("import_moneypro_csv");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();
    }

    @Test
    void llmPicksChatAndTextFieldPropagatesVerbatim() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("import_moneypro_csv", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"chat\",\"text\":\"Прикрепи CSV-файл и скажи как мапить счета.\"}")));

        StepVerifier.create(router.route("импортируй csv"))
                .assertNext(r -> {
                    assertThat(r.text()).isEqualTo("Прикрепи CSV-файл и скажи как мапить счета.");
                    assertThat(r.invokedTool()).isNull();
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        verify(dispatcher, never()).dispatch(anyString(), anyString());
    }

    @Test
    void llmReturnsNonJsonTreatsRawBodyAsChatReply() {
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("import_moneypro_csv", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "Извини, не понял запрос."))); // not JSON at all

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
        when(dispatcher.availableToolDefinitions()).thenReturn(List.of(toolDef("import_moneypro_csv", "x")));
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(reply("mock-large",
                "{\"action\":\"tool\",\"name\":\"import_moneypro_csv\",\"args\":{}}")));
        doThrow(new IllegalArgumentException("Missing required field: householdId"))
                .when(dispatcher).dispatch(eq("import_moneypro_csv"), eq("{}"));

        StepVerifier.create(router.route("импортируй"))
                .assertNext(r -> {
                    // The user gets a friendly error mentioning the tool name
                    // and the dispatcher's own message — no exception is
                    // bubbled up to the controller.
                    assertThat(r.text()).contains("import_moneypro_csv");
                    assertThat(r.text()).contains("Missing required field");
                    assertThat(r.invokedTool()).isEqualTo("import_moneypro_csv");
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
