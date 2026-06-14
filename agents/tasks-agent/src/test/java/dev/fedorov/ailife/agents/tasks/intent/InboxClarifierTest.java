package dev.fedorov.ailife.agents.tasks.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.TaskReviewClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.WeeklyReviewResult;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InboxClarifier} — mock {@link LlmClient} + {@link TaskReviewClient} exercise
 * the proposal / empty-inbox / failure branches without an external service.
 */
class InboxClarifierTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final TaskReviewClient review = mock(TaskReviewClient.class);
    // findAndRegisterModules() mirrors Spring's mapper (JavaTimeModule) so Instant fields on
    // TaskItemDto serialize — a bare ObjectMapper would throw and hit the degrade branch.
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentManifest manifest = new AgentManifest(
            "tasks", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the tasks agent.");
    private final Skill skill = new Skill("inbox-clarify", "Propose a GTD clarification.",
            "0.1.0", "tasks", List.of(), List.of("en", "ru"), "Clarify the inbox items.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final InboxClarifier clarifier =
            new InboxClarifier(llm, manifest, skills, review, json);

    @Test
    void proposesClarificationFromInboxItems() {
        UUID household = UUID.randomUUID();
        when(review.fetch(household)).thenReturn(Mono.just(new WeeklyReviewResult(
                household, 2, 0, 0,
                List.of(inboxItem("купить молоко"), inboxItem("позвонить врачу")),
                List.of(), List.of())));
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("mock-large", "«купить молоко» → @errand; «позвонить врачу» → @calls"));
        });

        StepVerifier.create(clarifier.clarify(message(household, "разбери инбокс")))
                .assertNext(r -> {
                    assertThat(r.agent()).isEqualTo("tasks");
                    assertThat(r.text()).contains("@errand");
                    assertThat(r.llmModel()).isEqualTo("mock-large");
                })
                .verifyComplete();

        // The skill body + the inbox items + the user's text reach the LLM.
        String body = seen.get().messages().toString();
        assertThat(body).contains("Clarify the inbox items.");
        assertThat(body).contains("купить молоко");
        assertThat(body).contains("разбери инбокс");
    }

    @Test
    void emptyInboxRepliesNothingToClarifyWithoutLlmCall() {
        UUID household = UUID.randomUUID();
        when(review.fetch(household)).thenReturn(Mono.just(new WeeklyReviewResult(
                household, 0, 0, 0, List.of(), List.of(), List.of())));

        StepVerifier.create(clarifier.clarify(message(household, "разбери инбокс")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("пуст");
                    assertThat(r.llmModel()).isNull();
                })
                .verifyComplete();

        verify(llm, never()).chat(any(LlmChatRequest.class));
    }

    @Test
    void reviewFailureDegradesToFriendlyMessage() {
        UUID household = UUID.randomUUID();
        when(review.fetch(household)).thenReturn(Mono.error(new RuntimeException("mcp-tasks down")));

        StepVerifier.create(clarifier.clarify(message(household, "разбери инбокс")))
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

    private static TaskItemDto inboxItem(String title) {
        return new TaskItemDto(UUID.randomUUID(), null, null, null, title, "inbox",
                null, null, null, null, null, "manual", null, null, null, Instant.now(), null);
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }
}
