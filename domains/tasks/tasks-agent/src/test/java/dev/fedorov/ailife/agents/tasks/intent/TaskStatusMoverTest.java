package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.ClarifyClient;
import dev.fedorov.ailife.agents.tasks.read.TaskReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.tasks.ClarifyTaskInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
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
 * Unit tests for {@link TaskStatusMover} (road-test #486, Track H.2 — the tasks state-move hole): mock
 * {@link LlmClient} + {@link TaskReads} + {@link ClarifyClient} exercise the confirm-before-change gate, the
 * ask-when-no-status re-ask ({@code missing}), the resume-affirmative clarify, the decline, and the
 * ambiguous / no-match branches. Mirrors {@link TaskEditorTest} + {@link TaskDeleterTest}.
 */
class TaskStatusMoverTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final TaskReads taskReads = mock(TaskReads.class);
    private final ClarifyClient clarify = mock(ClarifyClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "tasks", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the tasks agent.");
    private final Skill skill = new Skill("task-status", "Move a task to a new GTD status.",
            "0.1.0", "tasks", List.of(), List.of("en", "ru"), "Pick the task and the new status.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final TaskStatusMover mover =
            new TaskStatusMover(llm, manifest, skills, taskReads, clarify, json);

    @Test
    void markDoneConfirmsFirst() {
        UUID household = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt())).thenReturn(Mono.just(List.of(
                task(taskId, "купить молоко", "next"),
                task(UUID.randomUUID(), "позвонить маме", "next"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1,\"newStatus\":\"done\"}")));

        StepVerifier.create(mover.move(message(household, "отметь задачу про молоко выполненной")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Отметить задачу").contains("купить молоко").contains("выполненной");
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("flow").asString()).isEqualTo("task-status-confirm");
                    assertThat(r.pendingAction().path("taskId").asString()).isEqualTo(taskId.toString());
                    assertThat(r.pendingAction().path("newStatus").asString()).isEqualTo("done");
                })
                .verifyComplete();

        verify(clarify, never()).clarify(any());
    }

    @Test
    void pickedButNoStatusAsks() {
        UUID household = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt()))
                .thenReturn(Mono.just(List.of(task(taskId, "купить молоко", "next"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1}")));

        StepVerifier.create(mover.move(message(household, "поменяй статус задачи про молоко")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("В какой статус").contains("купить молоко");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(clarify, never()).clarify(any());
    }

    @Test
    void invalidStatusIsTreatedAsMissing() {
        UUID household = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt()))
                .thenReturn(Mono.just(List.of(task(taskId, "купить молоко", "next"))));
        // The model returned a status outside the GTD whitelist → re-ask, no lock.
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1,\"newStatus\":\"bogus\"}")));

        StepVerifier.create(mover.move(message(household, "переведи задачу про молоко")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("В какой статус");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(clarify, never()).clarify(any());
    }

    @Test
    void ambiguousTargetIsClarified() {
        UUID household = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt())).thenReturn(Mono.just(List.of(
                task(UUID.randomUUID(), "позвонить маме", "next"),
                task(UUID.randomUUID(), "позвонить в банк", "next"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"ambiguous\":[1,2]}")));

        StepVerifier.create(mover.move(message(household, "закрой звонок")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("несколько").contains("позвонить маме").contains("позвонить в банк");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(clarify, never()).clarify(any());
    }

    @Test
    void noMatchAsks() {
        UUID household = UUID.randomUUID();
        when(taskReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(taskReads.openTasksUnion(eq(List.of(household)), anyInt()))
                .thenReturn(Mono.just(List.of(task(UUID.randomUUID(), "купить молоко", "next"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{}")));

        StepVerifier.create(mover.move(message(household, "отметь задачу про отпуск выполненной")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл такую задачу");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(clarify, never()).clarify(any());
    }

    @Test
    void resumeAffirmativeClarifiesToNewStatus() {
        UUID taskId = UUID.randomUUID();
        when(clarify.clarify(any(ClarifyTaskInput.class)))
                .thenReturn(Mono.just(task(taskId, "купить молоко", "done")));

        ObjectNode pending = pending(taskId, "купить молоко");
        pending.put("newStatus", "done");

        StepVerifier.create(mover.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "да"), pending)))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Отметил").contains("купить молоко").contains("выполненной");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        ArgumentCaptor<ClarifyTaskInput> captor = ArgumentCaptor.forClass(ClarifyTaskInput.class);
        verify(clarify).clarify(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(taskId);
        assertThat(captor.getValue().status()).isEqualTo("done");
    }

    @Test
    void resumeDeclineLeavesIt() {
        UUID taskId = UUID.randomUUID();
        ObjectNode pending = pending(taskId, "купить молоко");
        pending.put("newStatus", "done");

        StepVerifier.create(mover.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "нет"), pending)))
                .assertNext(r -> assertThat(r.text()).contains("без изменений"))
                .verifyComplete();

        verify(clarify, never()).clarify(any());
    }

    private ObjectNode pending(UUID taskId, String title) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", "task-status-confirm");
        node.put("taskId", taskId.toString());
        node.put("title", title);
        return node;
    }

    private static NormalizedMessage message(UUID household, String text) {
        return new NormalizedMessage(UUID.randomUUID(), household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static TaskItemDto task(UUID id, String title, String status) {
        return new TaskItemDto(id, null, null, null, title, status,
                null, null, null, null, null, "manual", null, null, null, Instant.now(), null);
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }
}
