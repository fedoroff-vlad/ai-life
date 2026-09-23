package dev.fedorov.ailife.agents.inventory;

import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the packing session (IN-c) through the agent's HTTP surface. MockWebServers stand in for
 * mcp-inventory, mcp-media-processing and llm-gateway.
 *
 * <p>The session is the route-lock: {@code /intent} opens it and returns a {@code pendingAction}, then
 * every following turn arrives on {@code /resume} carrying that envelope back. These tests drive
 * exactly that sequence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BoxPackerTest {

    static MockWebServer mcpInventory;
    static MockWebServer mcpMediaProcessing;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpInventory = new MockWebServer();
        mcpMediaProcessing = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpInventory.start();
        mcpMediaProcessing.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpInventory.shutdown();
        mcpMediaProcessing.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("inventory-agent.mcp-inventory-url", () -> "http://localhost:" + mcpInventory.getPort());
        r.add("inventory-agent.mcp-media-processing-url",
                () -> "http://localhost:" + mcpMediaProcessing.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void openingABoxResolvesTheZoneAndLocksTheSession() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID householdId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        UUID containerId = UUID.randomUUID();

        // 1) the router picks box-packer, 2) box-packer extracts the open move.
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"box-packer\"}"));
        llmGateway.enqueue(llm("{\"action\":\"open\",\"label\":\"кухня — посуда\",\"zone\":\"кладовка\","
                + "\"kind\":\"box\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(new StorageZoneDto(
                zoneId, householdId, userId, "кладовка", null, null, null, Instant.now()))));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(container(
                containerId, householdId, userId, zoneId, "B-01", "кухня — посуда", "open"))));

        IntentResponse response = intent(new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "открой коробку «кухня — посуда» в кладовку", List.of(), "telegram", "1", Instant.now()));

        assertThat(response.text()).contains("B-01").contains("кладовка");
        // The session envelope is what re-routes the next photo here — without it there is no session.
        assertThat(response.pendingAction()).isNotNull();
        assertThat(response.pendingAction().path("flow").asString(null)).isEqualTo("box-packing");
        assertThat(response.pendingAction().path("containerId").asString(null))
                .isEqualTo(containerId.toString());
        assertThat(response.pendingAction().path("count").asInt(-1)).isZero();

        RecordedRequest zoneReq = take(mcpInventory);
        assertThat(zoneReq.getPath()).isEqualTo("/internal/zones");
        RecordedRequest containerReq = take(mcpInventory);
        assertThat(containerReq.getPath()).isEqualTo("/internal/containers");
        assertThat(containerReq.getBody().readUtf8()).contains("кухня — посуда");
    }

    @Test
    void aPhotoDuringTheSessionIsCaptionedSavedAndKeepsTheLock() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID householdId = UUID.randomUUID();
        UUID containerId = UUID.randomUUID();

        mcpMediaProcessing.enqueue(jsonResponse(json.writeValueAsString(
                new CaptionResult("чугунная сковорода", "mock-vision"))));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(new ItemDto(
                UUID.randomUUID(), containerId, "media-9", "чугунная сковорода", null, null, 1,
                Instant.now()))));

        IntentResponse response = resume(session(containerId, "B-01", "кухня — посуда", 2),
                new NormalizedMessage(userId, householdId, MessageScope.PRIVATE, null,
                        List.of(new Attachment("image", "image/jpeg", "media-9", null)),
                        "telegram", "2", Instant.now()));

        assertThat(response.text()).contains("чугунная сковорода").contains("3 предмета");
        // Still locked: the next photo must land in the same box without another question.
        assertThat(response.pendingAction()).isNotNull();
        assertThat(response.pendingAction().path("count").asInt(-1)).isEqualTo(3);
        assertThat(response.pendingAction().path("containerId").asString(null))
                .isEqualTo(containerId.toString());

        RecordedRequest captionReq = take(mcpMediaProcessing);
        assertThat(captionReq.getPath()).isEqualTo("/internal/caption");
        RecordedRequest itemReq = take(mcpInventory);
        assertThat(itemReq.getPath()).isEqualTo("/internal/items");
        assertThat(itemReq.getBody().readUtf8()).contains("media-9");
    }

    @Test
    void aUserWrittenCaptionWinsOverTheVisionModel() throws Exception {
        UUID containerId = UUID.randomUUID();
        int captionsBefore = mcpMediaProcessing.getRequestCount();
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(new ItemDto(
                UUID.randomUUID(), containerId, "media-10", "бабушкин сервиз", null, null, 1,
                Instant.now()))));

        IntentResponse response = resume(session(containerId, "B-01", "кухня", 0),
                new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                        "бабушкин сервиз",
                        List.of(new Attachment("image", "image/jpeg", "media-10", null)),
                        "telegram", "3", Instant.now()));

        assertThat(response.text()).contains("бабушкин сервиз");
        // The vision capability must not even be called when the owner already named the thing.
        assertThat(mcpMediaProcessing.getRequestCount()).isEqualTo(captionsBefore);
        assertThat(take(mcpInventory).getBody().readUtf8()).contains("бабушкин сервиз");
    }

    @Test
    void aPhotoWithNoOpenBoxAsksWhichContainerAndWritesNothing() {
        int inventoryBefore = mcpInventory.getRequestCount();
        int llmBefore = llmGateway.getRequestCount();

        IntentResponse response = intent(new NormalizedMessage(
                UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE, null,
                List.of(new Attachment("image", "image/jpeg", "media-11", null)),
                "telegram", "4", Instant.now()));

        assertThat(response.text()).contains("открой коробку");
        assertThat(response.pendingAction()).isNull();
        // Never guess a container: a misfiled thing is found in the wrong box months later. The photo
        // is also not worth an LLM turn — it is unambiguous, so the check stays deterministic.
        assertThat(mcpInventory.getRequestCount()).isEqualTo(inventoryBefore);
        assertThat(llmGateway.getRequestCount()).isEqualTo(llmBefore);
    }

    @Test
    void closingTheBoxMarksItPackedAndClearsTheLock() throws Exception {
        UUID containerId = UUID.randomUUID();

        llmGateway.enqueue(llm("{\"action\":\"close\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(container(
                containerId, UUID.randomUUID(), null, null, "B-07", "Новый год", "packed"))));

        IntentResponse response = resume(session(containerId, "B-07", "Новый год", 4),
                new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                        "закрой коробку", List.of(), "telegram", "5", Instant.now()));

        assertThat(response.text()).contains("B-07").contains("4 предмета");
        // Lock cleared — the next message routes normally again.
        assertThat(response.pendingAction()).isNull();

        RecordedRequest closeReq = take(mcpInventory);
        assertThat(closeReq.getPath()).isEqualTo("/internal/containers");
        String body = closeReq.getBody().readUtf8();
        assertThat(body).contains("packed").contains(containerId.toString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private IntentResponse intent(NormalizedMessage msg) {
        return http.post().uri("/agents/inventory/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .returnResult().getResponseBody();
    }

    private IntentResponse resume(JsonNode pendingAction, NormalizedMessage msg) {
        return http.post().uri("/agents/inventory/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ResumeRequest(msg, pendingAction))
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .returnResult().getResponseBody();
    }

    /** The envelope the orchestrator hands back while a box is open. */
    private JsonNode session(UUID containerId, String code, String label, int count) {
        var node = json.createObjectNode();
        node.put("flow", "box-packing");
        node.put("containerId", containerId.toString());
        node.put("code", code);
        node.put("label", label);
        node.put("count", count);
        return node;
    }

    private static ContainerDto container(UUID id, UUID household, UUID owner, UUID zone,
                                          String code, String label, String status) {
        return new ContainerDto(id, household, owner, zone, code, label, "box", "abcdef0123456789",
                status, null, null, Instant.now(), null);
    }

    private MockResponse llm(String content) {
        return jsonResponse(json.writeValueAsString(
                new LlmChatResponse("mock-large", content, "stop", new LlmUsage(20, 10, 30))));
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    private static RecordedRequest take(MockWebServer server) throws Exception {
        return server.takeRequest(5, TimeUnit.SECONDS);
    }
}
