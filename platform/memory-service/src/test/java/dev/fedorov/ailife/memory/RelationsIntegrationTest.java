package dev.fedorov.ailife.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.memory.PersonRelationsResponse;
import dev.fedorov.ailife.contracts.memory.RelationDto;
import dev.fedorov.ailife.contracts.memory.WriteRelationRequest;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relations API in isolation: no LLM in this path so no MockWebServer for
 * llm-gateway is needed. We still let memory-service auto-config its
 * LlmClient bean (it'll just sit idle).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RelationsIntegrationTest extends AbstractPostgresIntegrationTest {


    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);        // llm-client points nowhere — relations endpoints don't call the LLM.
        registry.add("ailife.llm-client.base-url", () -> "http://127.0.0.1:1");
    }

    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    static UUID household;
    static UUID otherHousehold;
    static UUID maria;
    static UUID vlad;

    @BeforeAll
    static void seedHouseholds(@Autowired JdbcTemplate jdbc) {
        applySchema("test-schema.sql");
        household = UUID.randomUUID();
        otherHousehold = UUID.randomUUID();
        maria = UUID.randomUUID();
        vlad = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "alpha");
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", otherHousehold, "beta");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private RelationDto write(UUID household, String subjectType, UUID subjectId, String edge,
                              String objectType, UUID objectId, String objectLabel, String source) {
        var req = new WriteRelationRequest(household, subjectType, subjectId, edge,
                objectType, objectId, objectLabel, null, source, null);
        return client().post().uri("/v1/relations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isOk()
                .expectBody(RelationDto.class)
                .returnResult().getResponseBody();
    }

    @Test
    void personRelationsReturnsOutgoingAndIncomingScopedToHousehold() {
        // Outgoing from Maria.
        write(household, "person", maria, "likes",
                "label", null, "loose-leaf earl grey tea", "chat");
        write(household, "person", maria, "likes",
                "label", null, "trail running", "chat");
        // Incoming to Maria.
        write(household, "person", vlad, "gave-gift-to",
                "person", maria, "Maria", "chat");
        // Leakage candidate: other household.
        write(otherHousehold, "person", maria, "likes",
                "label", null, "should-not-leak", "chat");

        PersonRelationsResponse resp = client().get()
                .uri(uri -> uri.path("/v1/graph/person/{id}/relations")
                        .queryParam("householdId", household)
                        .build(maria))
                .exchange()
                .expectStatus().isOk()
                .expectBody(PersonRelationsResponse.class)
                .returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.personId()).isEqualTo(maria);
        assertThat(resp.outgoing()).hasSize(2);
        assertThat(resp.outgoing())
                .extracting(RelationDto::objectLabel)
                .containsExactlyInAnyOrder("loose-leaf earl grey tea", "trail running");
        assertThat(resp.incoming()).hasSize(1);
        assertThat(resp.incoming().get(0).edge()).isEqualTo("gave-gift-to");
        assertThat(resp.incoming().get(0).subjectId()).isEqualTo(vlad);

        // Cross-household row must NOT appear.
        assertThat(resp.outgoing()).extracting(RelationDto::objectLabel)
                .doesNotContain("should-not-leak");
    }

    @Test
    void forgetRemovesRowAnd404OnUnknown() {
        RelationDto created = write(household, "person", vlad, "owns",
                "label", null, "bicycle", "chat");

        client().delete().uri("/v1/relations/{id}", created.id())
                .exchange()
                .expectStatus().isNoContent();

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM memory.relations WHERE id = ?",
                Integer.class, created.id());
        assertThat(rowCount).isEqualTo(0);

        client().delete().uri("/v1/relations/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void writeRejectsMissingRequiredFields() {
        var bad = new WriteRelationRequest(
                household, null, maria, "likes", "label", null, "tea", null, "chat", null);
        client().post().uri("/v1/relations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bad)
                .exchange()
                .expectStatus().is5xxServerError();
    }
}
