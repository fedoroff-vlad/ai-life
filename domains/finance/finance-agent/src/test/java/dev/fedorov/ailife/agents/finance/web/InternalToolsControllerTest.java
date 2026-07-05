package dev.fedorov.ailife.agents.finance.web;

import dev.fedorov.ailife.agents.finance.tools.ToolDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * WebFlux slice test for {@link InternalToolsController}. The real
 * {@link ToolDispatcher} is exercised by {@code ToolDispatcherTest} with a
 * hand-rolled provider; this slice mocks the dispatcher and just verifies
 * the HTTP wiring: status codes, body passthrough, error → 400.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class InternalToolsControllerTest {

    @Autowired WebTestClient http;
    @MockitoBean ToolDispatcher dispatcher;

    @Test
    void listReturnsKnownToolNames() {
        when(dispatcher.availableToolNames()).thenReturn(List.of("import_moneypro_csv", "echo"));

        http.get().uri("/agents/finance/internal/tools")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(b -> {
                    assertThat(b).contains("import_moneypro_csv");
                    assertThat(b).contains("echo");
                });
    }

    @Test
    void invokePassesJsonArgsThroughAndReturnsToolBodyVerbatim() {
        String args = "{\"householdId\":\"00000000-0000-0000-0000-000000000001\",\"dryRun\":true}";
        String toolResult = "{\"created\":0,\"skipped\":0,\"errors\":[]}";
        when(dispatcher.dispatch(eq("import_moneypro_csv"), eq(args))).thenReturn(toolResult);

        http.post().uri("/agents/finance/internal/tools/import_moneypro_csv")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(args)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo(toolResult);
    }

    @Test
    void unknownToolNameReturns400WithErrorBody() {
        doThrow(new IllegalArgumentException("Unknown tool: ghost. Known: [import_moneypro_csv]"))
                .when(dispatcher).dispatch(eq("ghost"), eq("{}"));

        http.post().uri("/agents/finance/internal/tools/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .value(b -> {
                    assertThat(b).contains("Unknown tool: ghost");
                    assertThat(b).contains("import_moneypro_csv");
                });
    }
}
