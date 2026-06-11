package dev.fedorov.ailife.tg.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.profile.HouseholdDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots gateway-telegram and points {@link ProfileClient}, {@link OrchestratorClient} and
 * {@link dev.fedorov.ailife.tg.media.MediaServiceClient} at MockWebServers. Each test gets a fresh
 * pair of mocks so request order is deterministic.
 */
@SpringBootTest(properties = "gateway.telegram.bot-token=")
class MessageProcessorTest {

    private static MockWebServer profile;
    private static MockWebServer orchestrator;
    private static MockWebServer media;

    @DynamicPropertySource
    static void wireServices(DynamicPropertyRegistry registry) {
        profile = new MockWebServer();
        orchestrator = new MockWebServer();
        media = new MockWebServer();
        try {
            profile.start();
            orchestrator.start();
            media.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start mock servers", e);
        }
        registry.add("gateway.services.profile-base-url",
                () -> "http://localhost:" + profile.getPort());
        registry.add("gateway.services.orchestrator-base-url",
                () -> "http://localhost:" + orchestrator.getPort());
        registry.add("gateway.services.media-base-url",
                () -> "http://localhost:" + media.getPort());
    }

    @AfterEach
    void drainRecordedRequests() throws InterruptedException {
        // Static mocks are reused across tests; consume anything left over so the next
        // test starts from a clean FIFO. takeRequest(timeout) returns null when empty
        // (getRequestCount() is cumulative — do NOT use it as a guard, that hangs).
        //noinspection StatementWithEmptyBody
        while (profile.takeRequest(50, TimeUnit.MILLISECONDS) != null) { }
        //noinspection StatementWithEmptyBody
        while (orchestrator.takeRequest(50, TimeUnit.MILLISECONDS) != null) { }
        //noinspection StatementWithEmptyBody
        while (media.takeRequest(50, TimeUnit.MILLISECONDS) != null) { }
    }

    @Autowired
    MessageProcessor processor;

    @Autowired
    ObjectMapper json;

    @Test
    void firstContactCreatesHouseholdAndUserThenReachesOrchestrator() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        profile.enqueue(new MockResponse().setResponseCode(404));
        profile.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new HouseholdDto(
                        householdId, "default household", Instant.now()))));
        profile.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new UserDto(
                        userId, householdId, "vlad", "en", 42L, "admin", Instant.now()))));
        orchestrator.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new IntentResponse("echo", "echoed: hi", "mock-large"))));

        var incoming = new MessageProcessor.IncomingMessage(
                42L, "vlad", "en", "hi", MessageScope.PRIVATE, "1");

        IntentResponse result = processor.process(incoming).block();

        assertThat(result).isNotNull();
        assertThat(result.agent()).isEqualTo("echo");
        assertThat(result.text()).isEqualTo("echoed: hi");

        assertThat(profile.takeRequest().getPath()).isEqualTo("/v1/users/by-telegram/42");
        assertThat(profile.takeRequest().getPath()).isEqualTo("/v1/households");
        assertThat(profile.takeRequest().getPath()).isEqualTo("/v1/users");

        RecordedRequest orchestratorRequest = orchestrator.takeRequest();
        assertThat(orchestratorRequest.getPath()).isEqualTo("/v1/intent");
        String body = orchestratorRequest.getBody().readUtf8();
        assertThat(body).contains("\"text\":\"hi\"");
        assertThat(body).contains("\"sourceChannel\":\"telegram\"");
        assertThat(body).contains(userId.toString());
        // Text-only message never touches media-service.
        assertThat(media.getRequestCount()).isZero();
    }

    @Test
    void returningUserSkipsCreation() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        profile.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new UserDto(
                        userId, householdId, "vlad", "ru-RU", 99L, "admin", Instant.now()))));
        orchestrator.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new IntentResponse("echo", "ok", "mock-large"))));

        var incoming = new MessageProcessor.IncomingMessage(
                99L, "vlad", "ru", "hello", MessageScope.PRIVATE, "7");

        IntentResponse result = processor.process(incoming).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).isEqualTo("ok");
        assertThat(profile.takeRequest().getPath()).isEqualTo("/v1/users/by-telegram/99");
        assertThat(profile.getRequestCount()).isEqualTo(1);

        RecordedRequest orchestratorRequest = orchestrator.takeRequest();
        assertThat(orchestratorRequest.getBody().readUtf8()).contains("\"text\":\"hello\"");
    }

    @Test
    void photoMessageUploadsToMediaAndAttachesStorageUri() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        profile.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new UserDto(
                        userId, householdId, "vlad", "ru", 99L, "admin", Instant.now()))));
        media.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new MediaObjectDto(
                        mediaId, householdId, userId, "image", "image/jpeg",
                        23L, "deadbeef", "telegram", Instant.now()))));
        orchestrator.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new IntentResponse("finance", "draft ready", "mock-large"))));

        byte[] photoBytes = "fake-jpeg-bytes-receipt".getBytes(StandardCharsets.UTF_8);
        var incoming = new MessageProcessor.IncomingMessage(
                99L, "vlad", "ru", "на чеке кофе", MessageScope.PRIVATE, "7",
                new MessageProcessor.IncomingPhoto(photoBytes, "image/jpeg", "receipt.jpg"));

        IntentResponse result = processor.process(incoming).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).isEqualTo("draft ready");

        // Bytes were uploaded to media-service as multipart.
        RecordedRequest mediaRequest = media.takeRequest();
        assertThat(mediaRequest.getMethod()).isEqualTo("POST");
        assertThat(mediaRequest.getPath()).isEqualTo("/v1/media");
        assertThat(mediaRequest.getHeader("content-type")).startsWith("multipart/form-data");
        String mediaBody = mediaRequest.getBody().readUtf8();
        assertThat(mediaBody).contains(householdId.toString());   // householdId form field
        assertThat(mediaBody).contains("telegram");               // source form field

        // The NormalizedMessage carries the media object id as an image attachment storageUri,
        // and the caption lands in text.
        RecordedRequest orchestratorRequest = orchestrator.takeRequest();
        String body = orchestratorRequest.getBody().readUtf8();
        assertThat(body).contains("\"text\":\"на чеке кофе\"");
        assertThat(body).contains("\"kind\":\"image\"");
        assertThat(body).contains("\"storageUri\":\"" + mediaId + "\"");
        assertThat(body).contains("\"mimeType\":\"image/jpeg\"");
    }
}
