package dev.fedorov.ailife.agents.coach;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manifest surface the orchestrator scrapes at startup: name/skills/intents present, both CO-2
 * skills declared, and the body carrying the doctrine every skill inherits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ManifestControllerTest {

    @Autowired WebTestClient http;

    @Test
    void manifestExposesNameSkillsAndIntents() {
        AgentManifest manifest = http.get().uri("/agents/coach/manifest")
                .exchange().expectStatus().isOk()
                .expectBody(AgentManifest.class).returnResult().getResponseBody();

        assertThat(manifest).isNotNull();
        assertThat(manifest.name()).isEqualTo("coach");
        assertThat(manifest.skills()).containsExactlyInAnyOrder("safety-check", "reflect");
        assertThat(manifest.intents()).isNotEmpty();
        assertThat(manifest.body()).contains("hypothesis");
    }
}
