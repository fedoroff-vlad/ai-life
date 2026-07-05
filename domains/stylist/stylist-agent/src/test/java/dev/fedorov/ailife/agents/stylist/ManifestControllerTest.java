package dev.fedorov.ailife.agents.stylist;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full stylist-agent context (MCP client disabled via test application.yml) and
 * verifies the manifest endpoint the orchestrator scrapes on startup. Proves the scaffold wires:
 * AGENT.md parsed, the agent-runtime beans (which need the profile/notifier/memory WebClients)
 * resolve, the LlmClient + StylistChat beans wire, and the agent registers as {@code stylist}
 * binding all three capabilities.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ManifestControllerTest {

    @Autowired WebTestClient web;

    @Test
    void manifestEndpointReturnsStylistManifest() {
        AgentManifest manifest = web.get().uri("/agents/stylist/manifest")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentManifest.class)
                .returnResult().getResponseBody();

        assertThat(manifest).isNotNull();
        assertThat(manifest.name()).isEqualTo("stylist");
        assertThat(manifest.mcp())
                .contains("mcp-wardrobe", "mcp-media-processing", "mcp-web");
        assertThat(manifest.body()).contains("stylist");
    }
}
