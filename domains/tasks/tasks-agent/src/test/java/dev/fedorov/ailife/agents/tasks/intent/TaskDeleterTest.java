package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.DeleteTaskClient;
import dev.fedorov.ailife.agents.tasks.read.TaskReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaskDeleter} (road-test #486, Track H.2 — the tasks delete hole): mock
 * {@link LlmClient} + {@link TaskReads} + {@link DeleteTaskClient} exercise the confirm-before-delete gate,
 * the resume-affirmative delete, the decline, and the no-match branch without an external service. Mirrors
 * {@link NextActionSuggesterTest} + calendar's {@code EventCancellerTest}.
 */
class TaskDeleterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final TaskReads taskReads = mock(TaskReads.class);
    private final DeleteTaskClient deleteTask = mock(DeleteTaskClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "tasks", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the tasks agent.");
    private final Skill skill = new Skill("task-delete", "Delete a task the user names.",
            "0.1.0", "tasks", List.of(), List.of("en", "ru"), "Pick the task to delete.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final TaskDeleter deleter =
            new TaskDeleter(llm, manifest, skills, taskReads, deleteTask, json);

    @Test
    void deleteByDescriptionConfirmsFirst() {
        UUID household = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt())).thenReturn(Mono.just(List.of(
                task(taskId, "позвонить маме"),
                task(UUID.randomUUID(), "купить молоко"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1}")));

        StepVerifier.create(deleter.delete(message(household, "удали задачу позвонить маме")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Удалить задачу").contains("позвонить маме");
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("flow").asString()).isEqualTo("task-delete-confirm");
                    assertThat(r.pendingAction().path("taskId").asString()).isEqualTo(taskId.toString());
                })
                .verifyComplete();

        // Confirm-before-delete: nothing was deleted on the first turn.
        verify(deleteTask, never()).delete(any());
    }

    @Test
    void ambiguousTargetIsClarified() {
        UUID household = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt())).thenReturn(Mono.just(List.of(
                task(UUID.randomUUID(), "позвонить маме"),
                task(UUID.randomUUID(), "позвонить в банк"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"ambiguous\":[1,2]}")));

        StepVerifier.create(deleter.delete(message(household, "удали звонок")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("несколько").contains("позвонить маме").contains("позвонить в банк");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(deleteTask, never()).delete(any());
    }

    @Test
    void noMatchAsks() {
        UUID household = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt()))
                .thenReturn(Mono.just(List.of(task(UUID.randomUUID(), "купить молоко"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{}")));

        StepVerifier.create(deleter.delete(message(household, "удали задачу про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл такую задачу");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(deleteTask, never()).delete(any());
    }

    @Test
    void resumeAffirmativeDeletes() {
        UUID taskId = UUID.randomUUID();
        when(deleteTask.delete(eq(taskId))).thenReturn(Mono.just(task(taskId, "позвонить маме")));

        StepVerifier.create(deleter.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "да"), pending(taskId, "позвонить маме"))))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Удалил задачу").contains("позвонить маме");
                    assertThat(r.pendingAction()).isNull();   // lock cleared
                })
                .verifyComplete();

        verify(deleteTask).delete(eq(taskId));
    }

    @Test
    void resumeDeclineLeavesIt() {
        UUID taskId = UUID.randomUUID();

        StepVerifier.create(deleter.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "нет"), pending(taskId, "позвонить маме"))))
                .assertNext(r -> assertThat(r.text()).contains("без изменений"))
                .verifyComplete();

        verify(deleteTask, never()).delete(any());
    }

    private ObjectNode pending(UUID taskId, String title) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", "task-delete-confirm");
        node.put("taskId", taskId.toString());
        node.put("title", title);
        return node;
    }

    private static NormalizedMessage message(UUID household, String text) {
        return new NormalizedMessage(UUID.randomUUID(), household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static TaskItemDto task(UUID id, String title) {
        return new TaskItemDto(id, null, null, null, title, "next",
                null, null, null, null, null, "manual", null, null, null, Instant.now(), null);
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }
}
