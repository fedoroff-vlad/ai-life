package dev.fedorov.ailife.agents.inventory;

import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filing one photo into a box that is already closed (IN-g3), through the agent's real HTTP surface.
 *
 * <p>The behaviour this pins down is the split at the front of {@code IntentController}: a photo <b>with
 * a caption</b> is the owner saying where it goes, a <b>bare</b> photo has nothing to go on and must still
 * ask. And the append itself must never file a thing into a box it only guessed — a misfiled thing is
 * found months later by accident, which is the failure the whole domain exists to prevent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BoxAppenderTest {

    private static final UUID B07 = UUID.randomUUID();
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final String MEDIA = "media-1";

    static MockWebServer mcpInventory;
    static MockWebServer mediaProcessing;
    static MockWebServer llmGateway;

    /** Every call the agent made, as "METHOD path" — what a test asserts instead of a positional take. */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();
    static final List<String> SAVED = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        mediaProcessing = new MockWebServer();
        mediaProcessing.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                CALLS.add("CAPTION " + request.getPath());
                try {
                    return json(MAPPER.writeValueAsString(new CaptionResult("серый повербанк", null)));
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
            }
        });
        mediaProcessing.start();
        mcpInventory = new MockWebServer();
        mcpInventory.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                CALLS.add(request.getMethod() + " " + path);
                try {
                    if (path.startsWith("/internal/items")) {
                        String body = request.getBody().readUtf8();
                        SAVED.add(body);
                        JsonNode in = MAPPER.readTree(body);
                        return json(MAPPER.writeValueAsString(new ItemDto(UUID.randomUUID(), B07, MEDIA,
                                in.path("title").asString(null), null, null, 1, Instant.now())));
                    }
                    if (path.startsWith("/internal/containers")) {
                        return json(MAPPER.writeValueAsString(List.of(container())));
                    }
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        mcpInventory.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpInventory.shutdown();
        mediaProcessing.shutdown();
        llmGateway.shutdown();
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
        SAVED.clear();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("inventory-agent.mcp-inventory-url", () -> "http://localhost:" + mcpInventory.getPort());
        r.add("inventory-agent.mcp-media-processing-url",
                () -> "http://localhost:" + mediaProcessing.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    /** The caption names the box AND the thing: it is filed, and vision is never asked. */
    @Test
    void aCaptionedPhotoIsFiledIntoTheNamedBox() {
        llmGateway.enqueue(llm("{\"container\":\"B-07\",\"title\":\"ёлочная гирлянда\"}"));

        IntentResponse resp = send("добавь в B-07 ёлочную гирлянду");

        assertThat(resp.text()).contains("ёлочная гирлянда").contains("B-07");
        assertThat(resp.pendingAction()).as("a single append must not route-lock the conversation").isNull();
        JsonNode saved = json.readTree(SAVED.get(0));
        assertThat(saved.path("containerId").asString()).isEqualTo(B07.toString());
        assertThat(saved.path("title").asString()).isEqualTo("ёлочная гирлянда");
        assertThat(saved.path("mediaId").asString()).isEqualTo(MEDIA);
        // The owner named the thing, so the vision model is not called at all.
        assertThat(CALLS).noneMatch(c -> c.startsWith("CAPTION"));
    }

    /** The caption only points at a box — then vision names the thing (the photo IS the record). */
    @Test
    void aCaptionThatOnlyNamesTheBoxLetsVisionNameTheThing() {
        llmGateway.enqueue(llm("{\"container\":\"B-07\"}"));

        IntentResponse resp = send("это в коробку B-07");

        assertThat(resp.text()).contains("повербанк");
        assertThat(CALLS).anyMatch(c -> c.startsWith("CAPTION"));
        assertThat(json.readTree(SAVED.get(0)).path("title").asString()).isEqualTo("серый повербанк");
    }

    /** A caption naming no box files nothing — it asks, because a misfiled thing is the expensive error. */
    @Test
    void aCaptionWithNoBoxAsksInsteadOfGuessing() {
        llmGateway.enqueue(llm("{\"title\":\"зарядка от ноутбука\"}"));

        IntentResponse resp = send("зарядка от ноутбука");

        assertThat(resp.text()).contains("В какую коробку");
        assertThat(SAVED).as("filed a thing into a box nobody named").isEmpty();
    }

    /** A box the household does not have is named back, not silently replaced by a near miss. */
    @Test
    void anUnknownBoxIsNamedBack() {
        llmGateway.enqueue(llm("{\"container\":\"B-99\",\"title\":\"шуруповёрт\"}"));

        IntentResponse resp = send("добавь в B-99 шуруповёрт");

        assertThat(resp.text()).contains("Не нашёл коробку").contains("B-99");
        assertThat(SAVED).isEmpty();
    }

    /** A bare photo keeps the deterministic ask — no caption, no LLM turn, nothing written. */
    @Test
    void aBarePhotoStillAsksWithoutAnyLlmTurn() {
        int llmBefore = llmGateway.getRequestCount();

        IntentResponse resp = send(null);

        assertThat(resp.text()).contains("Не знаю, в какую коробку");
        assertThat(llmGateway.getRequestCount()).as("a bare photo cost an LLM call").isEqualTo(llmBefore);
        assertThat(SAVED).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private IntentResponse send(String caption) {
        return http.post().uri("/agents/inventory/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NormalizedMessage(OWNER, HOUSEHOLD, MessageScope.PRIVATE, caption,
                        List.of(new Attachment("image", "image/jpeg", MEDIA, null)),
                        "telegram", "1", Instant.now()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .returnResult().getResponseBody();
    }

    private static ContainerDto container() {
        return new ContainerDto(B07, HOUSEHOLD, OWNER, null, "B-07", "кухня — посуда", "box",
                "demo-b07", "packed", null, null, Instant.now(), null);
    }

    private MockResponse llm(String content) {
        return json(json.writeValueAsString(
                new LlmChatResponse("mock-large", content, "stop", new LlmUsage(20, 10, 30))));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
