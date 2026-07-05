package dev.fedorov.ailife.agents.coordinator;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full coordinator-agent context and verifies the manifest endpoint the orchestrator scrapes
 * on startup. Proves the scaffold wires: AGENT.md parsed, the agent-runtime beans (Coordinator + the
 * profile/notifier/memory clients) resolve, the LlmClient + MultiDomainCoordinator beans wire, and the
 * agent registers as {@code coordinator} with the cross-cutting routing description + the surface trigger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ManifestControllerTest {

    @Autowired WebTestClient web;

    @Test
    void manifestEndpointReturnsCoordinatorManifest() {
        AgentManifest manifest = web.get().uri("/agents/coordinator/manifest")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentManifest.class)
                .returnResult().getResponseBody();

        assertThat(manifest).isNotNull();
        assertThat(manifest.name()).isEqualTo("coordinator");
        // The description is what makes the data-driven router pick this agent for cross-cutting requests.
        assertThat(manifest.description().toLowerCase()).contains("domain");
        assertThat(manifest.triggers()).anyMatch(t -> "coordinator.surface".equals(t.get("kind")));
        assertThat(manifest.body()).contains("coordinator");
    }
}
