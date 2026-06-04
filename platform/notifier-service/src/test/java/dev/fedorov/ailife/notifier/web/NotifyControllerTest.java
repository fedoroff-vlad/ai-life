package dev.fedorov.ailife.notifier.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.notify.NotifyRequest;
import dev.fedorov.ailife.contracts.profile.UserDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "notifier.internal-api-token=test-token")
class NotifyControllerTest {

    static MockWebServer profile;
    static MockWebServer gateway;

    @BeforeAll
    static void startMocks() throws Exception {
        profile = new MockWebServer();
        profile.start();
        gateway = new MockWebServer();
        gateway.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        profile.shutdown();
        gateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("notifier.profile-base-url", () -> "http://localhost:" + profile.getPort());
        r.add("notifier.gateway-base-url", () -> "http://localhost:" + gateway.getPort());
    }

    @AfterEach
    void drain() throws InterruptedException {
        //noinspection StatementWithEmptyBody
        while (profile.takeRequest(50, TimeUnit.MILLISECONDS) != null) { }
        //noinspection StatementWithEmptyBody
        while (gateway.takeRequest(50, TimeUnit.MILLISECONDS) != null) { }
    }

    @Autowired WebTestClient client;
    @Autowired ObjectMapper json;

    @Test
    void resolvesUserAndForwardsToGatewayWithBearerToken() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID householdId = UUID.randomUUID();

        profile.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new UserDto(
                        userId, householdId, "vlad", "ru-RU", 12345L, "admin", Instant.now()))));
        gateway.enqueue(new MockResponse().setResponseCode(204));

        client.post().uri("/v1/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NotifyRequest(userId, "hello"))
                .exchange()
                .expectStatus().isAccepted();

        RecordedRequest profileReq = profile.takeRequest();
        assertThat(profileReq.getPath()).isEqualTo("/v1/users/" + userId);

        RecordedRequest gatewayReq = gateway.takeRequest();
        assertThat(gatewayReq.getPath()).isEqualTo("/internal/send");
        assertThat(gatewayReq.getHeader("Authorization")).isEqualTo("Bearer test-token");
        String body = gatewayReq.getBody().readUtf8();
        assertThat(body).contains("\"telegramUserId\":12345");
        assertThat(body).contains("\"text\":\"hello\"");
    }

    @Test
    void returnsNotFoundWhenProfileIs404() {
        profile.enqueue(new MockResponse().setResponseCode(404));

        client.post().uri("/v1/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NotifyRequest(UUID.randomUUID(), "ping"))
                .exchange()
                .expectStatus().isNotFound();

        assertThat(gateway.getRequestCount()).isZero();
    }

    @Test
    void rejectsUserWithoutTelegramLinkAs422() throws Exception {
        UUID userId = UUID.randomUUID();
        profile.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new UserDto(
                        userId, UUID.randomUUID(), "no-tg", "ru-RU", null, "member", Instant.now()))));

        client.post().uri("/v1/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NotifyRequest(userId, "ping"))
                .exchange()
                .expectStatus().isEqualTo(422);
    }

    @Test
    void missingTextIs400() {
        client.post().uri("/v1/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NotifyRequest(UUID.randomUUID(), null))
                .exchange()
                .expectStatus().isBadRequest();
    }
}
