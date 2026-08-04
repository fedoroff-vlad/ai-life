package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The thin tally read/write (ADR-0002 item 8): verifies both endpoints' request shape and the best-effort
 * failure semantics — record swallows errors, policy resolves empty on {@code 204}/error so the caller falls
 * back to its static policy.
 */
class SharingLearningClientTest {

    private MockWebServer server;
    private SharingLearningClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        WebClient http = WebClient.builder().baseUrl(server.url("/").toString()).build();
        client = new SharingLearningClient(http);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void recordPostsTheDecisionToTheDecisionsEndpoint() throws Exception {
        UUID household = UUID.randomUUID();
        server.enqueue(new MockResponse().setResponseCode(204));

        StepVerifier.create(client.record(household, "calendar", "cat=birthday|kind=|member=false", SharingScope.SHARED))
                .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/v1/sharing/decisions");
        String body = req.getBody().readUtf8();
        assertThat(body).contains(household.toString()).contains("calendar")
                .contains("cat=birthday").contains("SHARED");
    }

    @Test
    void recordSwallowsErrors() {
        server.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(client.record(UUID.randomUUID(), "finance", "k", SharingScope.PRIVATE))
                .verifyComplete();
    }

    @Test
    void policyParsesTheLearnedDefaultAndHitsThePolicyEndpoint() throws Exception {
        UUID household = UUID.randomUUID();
        server.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody("{\"scope\":\"SHARED\",\"confidence\":0.8,\"total\":5}"));

        StepVerifier.create(client.policy(household, "calendar", "cat=birthday|kind=|member=false"))
                .assertNext(r -> {
                    assertThat(r.scope()).isEqualTo(SharingScope.SHARED);
                    assertThat(r.confidence()).isEqualTo(0.8);
                    assertThat(r.total()).isEqualTo(5);
                })
                .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).startsWith("/v1/sharing/policy?");
        assertThat(req.getPath()).contains("householdId=" + household)
                .contains("domain=calendar");
    }

    @Test
    void policyEmptyOn204Unseen() {
        server.enqueue(new MockResponse().setResponseCode(204));
        StepVerifier.create(client.policy(UUID.randomUUID(), "docs", "unseen"))
                .verifyComplete();
    }

    @Test
    void policyEmptyOnError() {
        server.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(client.policy(UUID.randomUUID(), "docs", "k"))
                .verifyComplete();
    }
}
