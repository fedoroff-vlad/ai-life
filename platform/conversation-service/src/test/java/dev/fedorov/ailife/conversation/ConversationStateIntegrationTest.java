package dev.fedorov.ailife.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import dev.fedorov.ailife.contracts.conversation.SetConversationStateRequest;
import dev.fedorov.ailife.conversation.domain.ConversationStateService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConversationStateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("ailife").withUsername("ailife").withPassword("ailife")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-schema.sql"),
                    "/docker-entrypoint-initdb.d/00-test-schema.sql");

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    static UUID householdId;

    @BeforeAll
    static void seed(@Autowired JdbcTemplate jdbc) {
        householdId = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", householdId, "h");
    }

    @Autowired ConversationStateService service;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate http;
    @LocalServerPort int port;

    @Test
    void setThenGetReturnsActiveStateAndUpsertReplaces() {
        UUID user = UUID.randomUUID();
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "receipt-confirm");
        pending.put("draftAmount", "-4.50");

        ConversationStateDto set = service.set(new SetConversationStateRequest(
                householdId, user, "telegram", "finance", pending, 600L));
        assertThat(set.routeLock()).isEqualTo("finance");
        assertThat(set.pendingAction().path("flow").asText()).isEqualTo("receipt-confirm");
        assertThat(set.expiresAt()).isAfter(java.time.Instant.now());

        assertThat(service.getActive(householdId, user, "telegram"))
                .get().satisfies(s -> {
                    assertThat(s.routeLock()).isEqualTo("finance");
                    assertThat(s.pendingAction().path("draftAmount").asText()).isEqualTo("-4.50");
                });

        // Upsert: a second set for the same key replaces (no duplicate row, new lock wins).
        ConversationStateDto replaced = service.set(new SetConversationStateRequest(
                householdId, user, "telegram", "tasks", null, 600L));
        assertThat(replaced.id()).isEqualTo(set.id());
        assertThat(replaced.routeLock()).isEqualTo("tasks");
        assertThat(service.getActive(householdId, user, "telegram"))
                .get().satisfies(s -> assertThat(s.routeLock()).isEqualTo("tasks"));
    }

    @Test
    void expiredStateIsNotReturned() {
        UUID user = UUID.randomUUID();
        // ttl in the past → already expired on read.
        service.set(new SetConversationStateRequest(
                householdId, user, "telegram", "finance", null, -1L));
        assertThat(service.getActive(householdId, user, "telegram")).isEmpty();
    }

    @Test
    void clearRemovesState() {
        UUID user = UUID.randomUUID();
        service.set(new SetConversationStateRequest(
                householdId, user, "telegram", "finance", null, 600L));
        service.clear(householdId, user, "telegram");
        assertThat(service.getActive(householdId, user, "telegram")).isEmpty();
    }

    @Test
    void restRoundTripPutGetDelete() {
        UUID user = UUID.randomUUID();
        var req = new SetConversationStateRequest(
                householdId, user, "telegram", "calendar", null, 600L);

        ResponseEntity<ConversationStateDto> put = http.exchange(
                url(""), org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(req), ConversationStateDto.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).isNotNull();
        assertThat(put.getBody().routeLock()).isEqualTo("calendar");

        ResponseEntity<ConversationStateDto> get = http.getForEntity(
                url("?householdId=" + householdId + "&userId=" + user + "&channel=telegram"),
                ConversationStateDto.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody().routeLock()).isEqualTo("calendar");

        http.delete(url("?householdId=" + householdId + "&userId=" + user + "&channel=telegram"));

        ResponseEntity<ConversationStateDto> afterDelete = http.getForEntity(
                url("?householdId=" + householdId + "&userId=" + user + "&channel=telegram"),
                ConversationStateDto.class);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private String url(String suffix) {
        return "http://localhost:" + port + "/v1/conversation-state" + suffix;
    }
}
