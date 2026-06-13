package dev.fedorov.ailife.agents.tasks;

import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TriggerControllerTest {

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

    @Test
    void unknownTriggerKind404sWhileNoSkillsShip() {
        var wake = new AgentWakeRequest(UUID.randomUUID(), UUID.randomUUID(), "tasks",
                "weekly.review", null);

        http.post().uri("/agents/tasks/triggers/weekly.review")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(wake)
                .exchange()
                .expectStatus().isNotFound();
    }
}
