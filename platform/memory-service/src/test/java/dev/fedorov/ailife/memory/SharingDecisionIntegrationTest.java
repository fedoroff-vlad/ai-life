package dev.fedorov.ailife.memory;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.sharing.LearnedSharingPolicyResponse;
import dev.fedorov.ailife.contracts.sharing.RecordSharingDecisionRequest;
import dev.fedorov.ailife.test.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The learned-decision tally behind memory-driven default-sharing (ADR-0002 item 8, DS-1) in isolation. No
 * LLM in this path (the "learning" is a deterministic majority vote over recorded counts), so no MockWebServer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "event-bus.enabled=false")
@AutoConfigureWebTestClient
class SharingDecisionIntegrationTest extends AbstractPostgresIntegrationTest {

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registerDataSource(registry);
        registry.add("ailife.llm-client.base-url", () -> "http://127.0.0.1:1");
        registry.add("memory.profile-base-url", () -> "http://127.0.0.1:1");
    }

    @LocalServerPort int port;

    static UUID household;
    static UUID otherHousehold;

    @BeforeAll
    static void seedHouseholds(@Autowired JdbcTemplate jdbc) {
        applySchema("test-schema.sql");
        household = UUID.randomUUID();
        otherHousehold = UUID.randomUUID();
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", household, "alpha");
        jdbc.update("INSERT INTO core.households (id, name) VALUES (?, ?)", otherHousehold, "beta");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private void record(UUID hh, String domain, String signalKey, SharingScope scope) {
        client().post().uri("/v1/sharing/decisions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RecordSharingDecisionRequest(hh, domain, signalKey, scope))
                .exchange().expectStatus().isNoContent();
    }

    private LearnedSharingPolicyResponse policy(UUID hh, String domain, String signalKey) {
        return client().get()
                .uri(uri -> uri.path("/v1/sharing/policy")
                        .queryParam("householdId", hh)
                        .queryParam("domain", domain)
                        .queryParam("signalKey", signalKey).build())
                .exchange().expectStatus().isOk()
                .expectBody(LearnedSharingPolicyResponse.class).returnResult().getResponseBody();
    }

    @Test
    void majorityVoteWinsWithConfidenceAndTotal() {
        String key = "calendar|meeting";
        record(household, "calendar", key, SharingScope.SHARED);
        record(household, "calendar", key, SharingScope.SHARED);
        record(household, "calendar", key, SharingScope.SHARED);
        record(household, "calendar", key, SharingScope.PRIVATE);

        LearnedSharingPolicyResponse learned = policy(household, "calendar", key);
        assertThat(learned).isNotNull();
        assertThat(learned.scope()).isEqualTo(SharingScope.SHARED);
        assertThat(learned.total()).isEqualTo(4);
        assertThat(learned.confidence()).isEqualTo(0.75);
    }

    @Test
    void tieBreaksToPrivateAtHalfConfidence() {
        String key = "finance|card";
        record(household, "finance", key, SharingScope.SHARED);
        record(household, "finance", key, SharingScope.PRIVATE);

        LearnedSharingPolicyResponse learned = policy(household, "finance", key);
        assertThat(learned.scope()).isEqualTo(SharingScope.PRIVATE);
        assertThat(learned.total()).isEqualTo(2);
        assertThat(learned.confidence()).isEqualTo(0.5);
    }

    @Test
    void unseenSignalReturns204() {
        client().get()
                .uri(uri -> uri.path("/v1/sharing/policy")
                        .queryParam("householdId", household)
                        .queryParam("domain", "docs")
                        .queryParam("signalKey", "never-seen").build())
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void tallyDoesNotLeakAcrossHouseholds() {
        String key = "tasks|chore";
        record(household, "tasks", key, SharingScope.SHARED);
        record(household, "tasks", key, SharingScope.SHARED);
        // The other household has one PRIVATE decision for the same signal — its own tally, isolated.
        record(otherHousehold, "tasks", key, SharingScope.PRIVATE);

        assertThat(policy(household, "tasks", key).scope()).isEqualTo(SharingScope.SHARED);
        LearnedSharingPolicyResponse other = policy(otherHousehold, "tasks", key);
        assertThat(other.scope()).isEqualTo(SharingScope.PRIVATE);
        assertThat(other.total()).isEqualTo(1);
    }

    @Test
    void recordRejectsMissingFields() {
        client().post().uri("/v1/sharing/decisions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new RecordSharingDecisionRequest(household, "  ", "k", SharingScope.SHARED))
                .exchange().expectStatus().is5xxServerError();
    }
}
