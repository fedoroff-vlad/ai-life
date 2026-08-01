package dev.fedorov.ailife.sharing;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared identity read (ADR-0002): verifies both endpoints' request shape and the failure semantics
 * the two former per-service clients had — 4xx on routing → empty; any error on the union → empty list.
 */
class ProfileSharingClientTest {

    private MockWebServer server;
    private ProfileSharingClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient http = WebClient.builder().baseUrl(server.url("/").toString()).build();
        client = new ProfileSharingClient(http);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void householdRoutingParsesSplitAndHitsTheRoutingEndpoint() throws Exception {
        UUID user = UUID.randomUUID();
        UUID personal = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        server.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody("{\"personalHouseholdId\":\"" + personal + "\",\"sharedHouseholdIds\":[\"" + family + "\"]}"));

        StepVerifier.create(client.householdRouting(user))
                .assertNext(r -> {
                    assertThat(r.personalHouseholdId()).isEqualTo(personal);
                    assertThat(r.sharedHouseholdIds()).containsExactly(family);
                })
                .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/users/" + user + "/household-routing");
    }

    @Test
    void householdRoutingEmptyOn404() {
        server.enqueue(new MockResponse().setResponseCode(404));
        StepVerifier.create(client.householdRouting(UUID.randomUUID()))
                .verifyComplete();
    }

    @Test
    void householdsParsesUnionAndHitsTheHouseholdsEndpoint() throws Exception {
        UUID user = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        server.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody("[\"" + a + "\",\"" + b + "\"]"));

        StepVerifier.create(client.households(user))
                .assertNext(set -> assertThat(set).containsExactly(a, b))
                .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/users/" + user + "/households");
    }

    @Test
    void householdsEmptyListOnError() {
        server.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(client.households(UUID.randomUUID()))
                .assertNext(set -> assertThat(set).isEmpty())
                .verifyComplete();
    }
}
