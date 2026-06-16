package dev.fedorov.ailife.agents.tasks.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.NextActionClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NextActionSuggester} — mock {@link LlmClient} + {@link NextActionClient}
 * exercise the rank / empty / failure branches without an external service. Mirrors
 * {@link InboxClarifierTest}.
 */
class NextActionSuggesterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final NextActionClient nextActions = mock(NextActionClient.class);
    // findAndRegisterModules() mirrors Spring's mapper so TaskItemDto's Instant fields serialize.
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentManifest manifest = new AgentManifest(
            "tasks", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the tasks agent.");
    private final Skill skill = new Skill("next-action-suggester", "Rank open next-actions.",
            "0.1.0", "tasks", List.of(), List.of("en", "ru"), "Rank the next-actions.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final NextActionSuggester suggester =
            new NextActionSuggester(llm, manifest, skills, nextActions, json);

    @Test
    void ranksOpenNextActions() {
        UUID household = UUID.randomUUID();
        when(nextActions.fetchNextActions(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                nextItem("позвонить врачу", "@calls"),
                nextItem("починить кран", "@home"))));
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("mock-large", "Сначала: позвонить врачу (@calls)."));
        });

        StepVerifier.create(suggester.suggest(message(household, "что мне сейчас сделать")))
                .assertNext(r -> {
                    assertThat(r.agent()).isEqualTo("tasks");
                    assertThat(r.text()).contains("позвонить врачу");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        String body = seen.get().messages().toString();
        assertThat(body).contains("Rank the next-actions.");
        assertThat(body).contains("починить кран");
        assertThat(body).contains("что мне сейчас сделать");
    }

    @Test
    void emptyNextActionsRepliesWithoutLlmCall() {
        UUID household = UUID.randomUUID();
        when(nextActions.fetchNextActions(eq(household), anyInt())).thenReturn(Mono.just(List.of()));

        StepVerifier.create(suggester.suggest(message(household, "что дальше")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Нет открытых next-action");
                    assertThat(r.llmModel()).isNull();
                })
                .verifyComplete();

        verify(llm, never()).chat(any(LlmChatRequest.class));
    }

    @Test
    void fetchFailureDegradesToFriendlyMessage() {
        UUID household = UUID.randomUUID();
        when(nextActions.fetchNextActions(eq(household), anyInt()))
                .thenReturn(Mono.error(new RuntimeException("mcp-tasks down")));

        StepVerifier.create(suggester.suggest(message(household, "что дальше")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не удалось");
                    assertThat(r.llmModel()).isNull();
                })
                .verifyComplete();

        verify(llm, never()).chat(any(LlmChatRequest.class));
    }

    private static NormalizedMessage message(UUID household, String text) {
        return new NormalizedMessage(UUID.randomUUID(), household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static TaskItemDto nextItem(String title, String context) {
        return new TaskItemDto(UUID.randomUUID(), null, null, null, title, "next",
                context, null, null, null, null, "manual", null, null, null, Instant.now(), null);
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }
}
