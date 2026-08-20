package dev.fedorov.ailife.agents.tasks.web;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agents.tasks.http.DeleteTaskClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the tasks {@code undo} action (road-test #486, Track H — the reversal side). */
class ActionControllerTest {

    private final DeleteTaskClient deleteTask = mock(DeleteTaskClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ActionController controller = new ActionController(deleteTask, json);

    private AgentActionRequest undoRequest(String taskId) {
        ObjectNode args = json.createObjectNode();
        if (taskId != null) {
            args.put("taskId", taskId);
        }
        return new AgentActionRequest("tasks", "undo", UUID.randomUUID(), UUID.randomUUID(),
                "orchestrator", args);
    }

    private static TaskItemDto task(UUID id, String title) {
        return new TaskItemDto(id, UUID.randomUUID(), null, null, title, "inbox", null, null,
                null, null, null, "telegram", null, null, null, null, null);
    }

    @Test
    void undoDeletesTheTaskAndConfirmsWithItsTitle() {
        UUID id = UUID.randomUUID();
        when(deleteTask.delete(eq(id))).thenReturn(Mono.just(task(id, "купить молоко")));

        StepVerifier.create(controller.action("undo", undoRequest(id.toString())))
                .assertNext(result -> {
                    assertThat(result.ok()).isTrue();
                    assertThat(result.result().path("message").asString()).isEqualTo("Удалил: «купить молоко».");
                })
                .verifyComplete();

        verify(deleteTask).delete(id);
    }

    @Test
    void undoWithoutTaskIdIsRejectedWithoutDeleting() {
        StepVerifier.create(controller.action("undo", undoRequest(null)))
                .assertNext(result -> {
                    assertThat(result.ok()).isFalse();
                    assertThat(result.error()).contains("taskId");
                })
                .verifyComplete();

        verify(deleteTask, never()).delete(any(UUID.class));
    }

    @Test
    void undoOfAnAlreadyDeletedTaskIsSurfacedHonestly() {
        UUID id = UUID.randomUUID();
        when(deleteTask.delete(eq(id)))
                .thenReturn(Mono.error(new RuntimeException("404 Not Found")));

        StepVerifier.create(controller.action("undo", undoRequest(id.toString())))
                .assertNext(result -> {
                    assertThat(result.ok()).isFalse();
                    assertThat(result.error()).contains("уже удалена");
                })
                .verifyComplete();
    }

    @Test
    void unknownActionIsRejected() {
        StepVerifier.create(controller.action("frobnicate", undoRequest(UUID.randomUUID().toString())))
                .assertNext(result -> {
                    assertThat(result.ok()).isFalse();
                    assertThat(result.error()).contains("unknown action");
                })
                .verifyComplete();
    }
}
