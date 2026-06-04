package dev.fedorov.ailife.agents.calendar;

import dev.fedorov.ailife.agents.calendar.manifest.AgentManifest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManifestControllerTest {

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
        http.get().uri("/manifest")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentManifest.class)
                .value(m -> {
                    assertThat(m.name()).isEqualTo("calendar");
                    assertThat(m.skills()).isEmpty();
                    assertThat(m.body()).contains("English");
                });
    }
}
