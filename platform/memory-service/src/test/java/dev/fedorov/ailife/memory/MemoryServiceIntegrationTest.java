package dev.fedorov.ailife.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class MemoryServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife")
            .withUsername("ailife")
            .withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    static MockWebServer llmGateway;

    /**
     * Returns a deterministic 384-dim vector seeded off the input string. Two
     * identical inputs produce identical embeddings; different inputs produce
     * different ones with non-trivial cosine distance.
     */
    private static float[] embeddingFor(String text) {
        Random rnd = new Random(text.hashCode());
        float[] v = new float[384];
        for (int i = 0; i < v.length; i++) {
            v[i] = rnd.nextFloat() * 2f - 1f;
        }
        return v;
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) throws IOException {
        llmGateway = new MockWebServer();
        llmGateway.setDispatcher(new Dispatcher() {
            private final ObjectMapper json = new ObjectMapper();

            @Override
            public MockResponse dispatch(RecordedRequest req) {
                try {
                    var node = json.readTree(req.getBody().readUtf8());
                    String input = node.get("inputs").get(0).asText();
                    LlmEmbedResponse body = new LlmEmbedResponse(
                            "mock-embed", List.of(embeddingFor(input)), new LlmUsage(0, 0, 0));
                    return new MockResponse()
                            .setHeader("content-type", "application/json")
                            .setBody(json.writeValueAsString(body));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(e.toString());
                }
            }
        });
        llmGateway.start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("ailife.llm-client.base-url",
                () -> "http://localhost:" + llmGateway.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (llmGateway != null) llmGateway.shutdown();
    }

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    static UUID household;
    static UUID otherHousehold;

    @BeforeAll
    static void seedHouseholds(@Autowired JdbcTemplate jdbc) {
        household = UUID.randomUUID();
        otherHousehold = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "alpha");
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", otherHousehold, "beta");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void writeStoresEmbeddedRowAndForgetRemovesIt() {
        var req = new WriteMemoryRequest(
                household, null, null, "chat", "Maria loves loose-leaf earl grey tea.", null);

        MemoryDto written = client().post().uri("/v1/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MemoryDto.class)
                .returnResult().getResponseBody();

        assertThat(written).isNotNull();
        assertThat(written.id()).isNotNull();
        assertThat(written.source()).isEqualTo("chat");

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM memory.memories WHERE id = ?",
                Integer.class, written.id());
        assertThat(rowCount).isEqualTo(1);

        // forget
        client().delete().uri("/v1/memories/{id}", written.id())
                .exchange()
                .expectStatus().isNoContent();

        Integer after = jdbc.queryForObject(
                "SELECT count(*) FROM memory.memories WHERE id = ?",
                Integer.class, written.id());
        assertThat(after).isEqualTo(0);

        client().delete().uri("/v1/memories/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void recallReturnsClosestHitsScopedToHousehold() {
        // Seed three memories in our household, one in a different household.
        write("Maria's favourite tea is earl grey.", household, null, null);
        write("Vlad runs trails on weekends.", household, null, null);
        write("Movie night is usually Friday.", household, null, null);
        write("Otherwise unrelated household memory.", otherHousehold, null, null);

        // Query close to the tea memory.
        var req = new RecallMemoryRequest(household, null, null, "Maria's favourite tea is earl grey.", 3);
        List<RecallMemoryHit> hits = client().post().uri("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(RecallMemoryHit.class)
                .returnResult().getResponseBody();

        assertThat(hits).isNotNull().hasSizeBetween(1, 3);
        // The exact-text seed must come first (distance ~0).
        assertThat(hits.get(0).memory().text()).isEqualTo("Maria's favourite tea is earl grey.");
        assertThat(hits.get(0).distance()).isLessThan(0.01);
        // None of the hits leak the other household's row.
        assertThat(hits).noneMatch(h -> "Otherwise unrelated household memory.".equals(h.memory().text()));
    }

    @Test
    void recallNarrowsByPersonScope() {
        UUID maria = UUID.randomUUID();
        UUID vlad = UUID.randomUUID();
        write("Maria likes hiking and books.", household, null, maria);
        write("Vlad prefers cycling.", household, null, vlad);
        write("Household movie night Friday.", household, null, null);

        var req = new RecallMemoryRequest(household, null, maria, "What does Maria enjoy?", 5);
        List<RecallMemoryHit> hits = client().post().uri("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(RecallMemoryHit.class)
                .returnResult().getResponseBody();

        assertThat(hits).isNotNull();
        // Vlad's person-scoped memory must NOT appear; Maria's MUST.
        assertThat(hits).extracting(h -> h.memory().text())
                .doesNotContain("Vlad prefers cycling.")
                .contains("Maria likes hiking and books.");
    }

    private void write(String text, UUID household, UUID userId, UUID personId) {
        var req = new WriteMemoryRequest(household, userId, personId, "test-seed", text, null);
        client().post().uri("/v1/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk();
    }
}
