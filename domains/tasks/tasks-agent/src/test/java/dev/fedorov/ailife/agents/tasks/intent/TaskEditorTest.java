package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.UpdateTaskClient;
import dev.fedorov.ailife.agents.tasks.read.TaskReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.UpdateTaskInput;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * Unit tests for {@link TaskEditor} (road-test #486, Track H.2 — the tasks edit hole): mock
 * {@link LlmClient} + {@link TaskReads} + {@link UpdateTaskClient} exercise the confirm-before-change gate,
 * the ask-when-no-change re-ask ({@code missing}), the resume-affirmative update, the decline, and the
 * ambiguous / no-match branches without an external service. Mirrors {@link TaskDeleterTest} + notes'
 * {@code NoteEditorTest}.
 */
class TaskEditorTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final TaskReads taskReads = mock(TaskReads.class);
    private final UpdateTaskClient updateTask = mock(UpdateTaskClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "tasks", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the tasks agent.");
    private final Skill skill = new Skill("task-edit", "Edit a task the user names.",
            "0.1.0", "tasks", List.of(), List.of("en", "ru"), "Pick the task and the change.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final TaskEditor editor =
            new TaskEditor(llm, manifest, skills, taskReads, updateTask, json);

    @Test
    void renameConfirmsFirst() {
        UUID household = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt())).thenReturn(Mono.just(List.of(
                task(taskId, "купить молоко"),
                task(UUID.randomUUID(), "позвонить маме"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1,\"newTitle\":\"купить овсяное молоко\"}")));

        StepVerifier.create(editor.edit(message(household, "переименуй задачу про молоко в купить овсяное молоко")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Изменить задачу").contains("купить молоко")
                            .contains("купить овсяное молоко");
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("flow").asString()).isEqualTo("task-edit-confirm");
                    assertThat(r.pendingAction().path("taskId").asString()).isEqualTo(taskId.toString());
                    assertThat(r.pendingAction().path("newTitle").asString()).isEqualTo("купить овсяное молоко");
                })
                .verifyComplete();

        // Confirm-before-change: nothing was written on the first turn.
        verify(updateTask, never()).update(any(), any());
    }

    @Test
    void pickedButNoChangeAsksWhat() {
        UUID household = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt()))
                .thenReturn(Mono.just(List.of(task(taskId, "купить молоко"))));
        // The LLM resolved the target but the user did not say what to change.
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1}")));

        StepVerifier.create(editor.edit(message(household, "поправь задачу про молоко")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Что изменить").contains("купить молоко");
                    assertThat(r.pendingAction()).isNull();   // no lock — just a question
                })
                .verifyComplete();

        verify(updateTask, never()).update(any(), any());
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

        StepVerifier.create(editor.edit(message(household, "переименуй звонок")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("несколько")
                            .contains("позвонить маме").contains("позвонить в банк");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(updateTask, never()).update(any(), any());
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

        StepVerifier.create(editor.edit(message(household, "переименуй задачу про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл такую задачу");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(updateTask, never()).update(any(), any());
    }

    @Test
    void resumeAffirmativeUpdates() {
        UUID taskId = UUID.randomUUID();
        when(updateTask.update(eq(taskId), any(UpdateTaskInput.class)))
                .thenReturn(Mono.just(task(taskId, "купить овсяное молоко")));

        ObjectNode pending = pending(taskId, "купить молоко");
        pending.put("newTitle", "купить овсяное молоко");

        StepVerifier.create(editor.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "да"), pending)))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Изменил задачу").contains("купить молоко");
                    assertThat(r.pendingAction()).isNull();   // lock cleared
                })
                .verifyComplete();

        ArgumentCaptor<UpdateTaskInput> captor = ArgumentCaptor.forClass(UpdateTaskInput.class);
        verify(updateTask).update(eq(taskId), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("купить овсяное молоко");
        assertThat(captor.getValue().id()).isEqualTo(taskId);
    }

    @Test
    void resumeDeclineLeavesIt() {
        UUID taskId = UUID.randomUUID();
        ObjectNode pending = pending(taskId, "купить молоко");
        pending.put("newTitle", "купить овсяное молоко");

        StepVerifier.create(editor.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "нет"), pending)))
                .assertNext(r -> assertThat(r.text()).contains("без изменений"))
                .verifyComplete();

        verify(updateTask, never()).update(any(), any());
    }

    private ObjectNode pending(UUID taskId, String title) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", "task-edit-confirm");
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
