package dev.fedorov.ailife.conversation;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import dev.fedorov.ailife.contracts.conversation.SetConversationStateRequest;
import dev.fedorov.ailife.conversation.domain.ConversationStateService;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ConversationStateIntegrationTest extends AbstractPostgresIntegrationTest {


    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        registerDataSource(r);    }

    static UUID householdId;

    @BeforeAll
    static void seed(@Autowired JdbcTemplate jdbc) {
        applySchema("test-schema.sql");
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
                householdId, user, "telegram", "finance", pending, null, null, null, 600L));
        assertThat(set.routeLock()).isEqualTo("finance");
        assertThat(set.pendingAction().path("flow").asString()).isEqualTo("receipt-confirm");
        assertThat(set.expiresAt()).isAfter(java.time.Instant.now());

        assertThat(service.getActive(householdId, user, "telegram"))
                .get().satisfies(s -> {
                    assertThat(s.routeLock()).isEqualTo("finance");
                    assertThat(s.pendingAction().path("draftAmount").asString()).isEqualTo("-4.50");
                });

        // Upsert: a second set for the same key replaces (no duplicate row, new lock wins).
        ConversationStateDto replaced = service.set(new SetConversationStateRequest(
                householdId, user, "telegram", "tasks", null, null, null, null, 600L));
        assertThat(replaced.id()).isEqualTo(set.id());
        assertThat(replaced.routeLock()).isEqualTo("tasks");
        assertThat(service.getActive(householdId, user, "telegram"))
                .get().satisfies(s -> assertThat(s.routeLock()).isEqualTo("tasks"));
    }

    @Test
    void lastRouteRoundTripsIndependentlyOfTheLock() {
        UUID user = UUID.randomUUID();
        // A fresh routing records last_route + an optional trace with no lock (misroute-repair #484,
        // why-trace #485/G2). The trace is a short payload-free line of what the agent read/wrote.
        ConversationStateDto set = service.set(new SetConversationStateRequest(
                householdId, user, "telegram", null, null, "notes", "запиши купить молоко",
                "wrote: note «купить молоко»", 600L));
        assertThat(set.routeLock()).isNull();
        assertThat(set.lastRouteAgent()).isEqualTo("notes");
        assertThat(set.lastRouteText()).isEqualTo("запиши купить молоко");
        assertThat(set.lastRouteTrace()).isEqualTo("wrote: note «купить молоко»");

        assertThat(service.getActive(householdId, user, "telegram"))
                .get().satisfies(s -> {
                    assertThat(s.lastRouteAgent()).isEqualTo("notes");
                    assertThat(s.lastRouteText()).isEqualTo("запиши купить молоко");
                    assertThat(s.lastRouteTrace()).isEqualTo("wrote: note «купить молоко»");
                });
    }

    @Test
    void expiredStateIsNotReturned() {
        UUID user = UUID.randomUUID();
        // ttl in the past → already expired on read.
        service.set(new SetConversationStateRequest(
                householdId, user, "telegram", "finance", null, null, null, null, -1L));
        assertThat(service.getActive(householdId, user, "telegram")).isEmpty();
    }

    @Test
    void clearRemovesState() {
        UUID user = UUID.randomUUID();
        service.set(new SetConversationStateRequest(
                householdId, user, "telegram", "finance", null, null, null, null, 600L));
        service.clear(householdId, user, "telegram");
        assertThat(service.getActive(householdId, user, "telegram")).isEmpty();
    }

    @Test
    void restRoundTripPutGetDelete() {
        UUID user = UUID.randomUUID();
        var req = new SetConversationStateRequest(
                householdId, user, "telegram", "calendar", null, null, null, null, 600L);

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
