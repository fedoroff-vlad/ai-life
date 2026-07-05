package dev.fedorov.ailife.agents.docs;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full docs-agent context (MCP client disabled via test application.yml) and verifies the
 * manifest endpoint the orchestrator scrapes on startup. Proves the scaffold wires: AGENT.md parsed,
 * the agent-runtime beans (which need the profile/notifier/memory WebClients) resolve, the LlmClient +
 * DocsChat + DocArchiver (with the OcrClient/DocumentClient) beans wire, and the agent registers as
 * {@code docs} binding mcp-docs + mcp-media-processing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ManifestControllerTest {

    @Autowired WebTestClient web;

    @Test
    void manifestEndpointReturnsDocsManifest() {
        AgentManifest manifest = web.get().uri("/agents/docs/manifest")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentManifest.class)
                .returnResult().getResponseBody();

        assertThat(manifest).isNotNull();
        assertThat(manifest.name()).isEqualTo("docs");
        assertThat(manifest.mcp()).contains("mcp-docs", "mcp-media-processing");
        assertThat(manifest.skills()).contains("doc-archiver", "doc-finder");
    }
}
