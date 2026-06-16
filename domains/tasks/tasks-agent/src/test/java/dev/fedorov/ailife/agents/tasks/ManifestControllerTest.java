package dev.fedorov.ailife.agents.tasks;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManifestControllerTest {

    static MockWebServer llmGateway;

    @BeforeAll
    static void startMockLlm() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
    }

    @AfterAll
    static void stopMockLlm() throws Exception {
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired AgentManifest loaded;

    @Test
    void agentMdParsesAtStartup() {
        assertThat(loaded.name()).isEqualTo("tasks");
        assertThat(loaded.port()).isEqualTo(8096);
        assertThat(loaded.mcp()).contains("mcp-tasks");
        assertThat(loaded.intents())
                .extracting(m -> m.get("example"))
                .anyMatch(s -> s.contains("call the dentist"));
        assertThat(loaded.body()).contains("tasks agent");
    }

    @Test
    void getManifestReturnsTheParsedFile() {
        http.get().uri("/agents/tasks/manifest")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentManifest.class)
                .value(m -> {
                    assertThat(m.name()).isEqualTo("tasks");
                    assertThat(m.body()).contains("GTD");
                });
    }
}
