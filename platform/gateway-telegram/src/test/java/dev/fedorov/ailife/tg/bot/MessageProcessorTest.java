package dev.fedorov.ailife.tg.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.profile.HouseholdDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots gateway-telegram and points {@link ProfileClient} and {@link OrchestratorClient}
 * at MockWebServers. Each test gets a fresh pair of mocks so request order is
 * deterministic.
 */
@SpringBootTest(properties = "gateway.telegram.bot-token=")
class MessageProcessorTest {

    private static MockWebServer profile;
    private static MockWebServer orchestrator;

    @DynamicPropertySource
    static void wireServices(DynamicPropertyRegistry registry) {
        profile = new MockWebServer();
        orchestrator = new MockWebServer();
        try {
            profile.start();
            orchestrator.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start mock servers", e);
        }
        registry.add("gateway.services.profile-base-url",
                () -> "http://localhost:" + profile.getPort());
        registry.add("gateway.services.orchestrator-base-url",
                () -> "http://localhost:" + orchestrator.getPort());
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
}
