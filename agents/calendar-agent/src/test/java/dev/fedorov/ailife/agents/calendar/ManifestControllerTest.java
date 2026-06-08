package dev.fedorov.ailife.agents.calendar;

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
        assertThat(loaded.name()).isEqualTo("calendar");
        assertThat(loaded.port()).isEqualTo(8086);
        assertThat(loaded.mcp()).contains("mcp-caldav");
        assertThat(loaded.triggers())
                .extracting(m -> m.get("kind"))
                .contains("birthday.greet", "gift.recommend");
        assertThat(loaded.intents()).isNotEmpty();
        assertThat(loaded.body()).contains("calendar agent");
    }

    @Test
    void getManifestReturnsTheParsedFile() {
        http.get().uri("/agents/calendar/manifest")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentManifest.class)
                .value(m -> {
                    assertThat(m.name()).isEqualTo("calendar");
                    // PR32 backfilled the skills list — used by the loader
                    // hardening to fail-fast on a missing SKILL.md.
                    assertThat(m.skills())
                            .containsExactly("birthday-greeter", "gift-recommender");
                    assertThat(m.body()).contains("English");
                });
    }
}
