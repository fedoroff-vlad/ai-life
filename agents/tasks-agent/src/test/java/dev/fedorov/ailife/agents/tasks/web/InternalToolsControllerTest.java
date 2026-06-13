package dev.fedorov.ailife.agents.tasks.web;

import dev.fedorov.ailife.agents.tasks.tools.ToolDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * WebFlux slice test for {@link InternalToolsController}. The real {@link ToolDispatcher} is
 * exercised by {@code ToolDispatcherTest}; this mocks it and verifies the HTTP wiring: status
 * codes, body passthrough, error → 400. Mirrors finance's {@code InternalToolsControllerTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalToolsControllerTest {

    @Autowired WebTestClient http;
    @MockBean ToolDispatcher dispatcher;

    @Test
    void listReturnsKnownToolNames() {
        when(dispatcher.availableToolNames()).thenReturn(List.of("add_task", "list_tasks"));

        http.get().uri("/agents/tasks/internal/tools")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(b -> {
                    assertThat(b).contains("add_task");
                    assertThat(b).contains("list_tasks");
                });
    }

    @Test
    void invokePassesJsonArgsThroughAndReturnsToolBodyVerbatim() {
        String args = "{\"householdId\":\"00000000-0000-0000-0000-000000000001\",\"title\":\"milk\"}";
        String toolResult = "{\"id\":\"abc\",\"status\":\"inbox\"}";
        when(dispatcher.dispatch(eq("add_task"), eq(args))).thenReturn(toolResult);

        http.post().uri("/agents/tasks/internal/tools/add_task")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(args)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(toolResult);
    }

    @Test
    void unknownToolNameReturns400WithErrorBody() {
        doThrow(new IllegalArgumentException("Unknown tool: ghost. Known: [add_task]"))
                .when(dispatcher).dispatch(eq("ghost"), eq("{}"));

        http.post().uri("/agents/tasks/internal/tools/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(b -> {
                    assertThat(b).contains("Unknown tool: ghost");
                    assertThat(b).contains("add_task");
                });
    }
}
