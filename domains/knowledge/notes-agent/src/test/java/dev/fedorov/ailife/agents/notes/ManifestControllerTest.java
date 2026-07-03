package dev.fedorov.ailife.agents.notes;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full notes-agent context and verifies the manifest endpoint the orchestrator scrapes on
 * startup. Proves the scaffold wires: AGENT.md parsed, the agent-runtime beans (profile/notifier/memory
 * WebClients) resolve, the LlmClient + NotesChat + NoteWriter/NoteFinder (with the NoteClient over the
 * shared memory-service WebClient) beans wire, and the agent registers as {@code notes} with no MCP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ManifestControllerTest {

    @Autowired WebTestClient web;

    @Test
    void manifestEndpointReturnsNotesManifest() {
        AgentManifest manifest = web.get().uri("/agents/notes/manifest")
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentManifest.class)
                .returnResult().getResponseBody();

        assertThat(manifest).isNotNull();
        assertThat(manifest.name()).isEqualTo("notes");
        assertThat(manifest.skills()).contains("note-writer", "note-finder");
        assertThat(manifest.mcp()).isEmpty();
    }
}
