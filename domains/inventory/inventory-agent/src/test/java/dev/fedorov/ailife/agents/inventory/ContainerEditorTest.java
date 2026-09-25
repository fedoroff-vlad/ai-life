package dev.fedorov.ailife.agents.inventory;

import dev.fedorov.ailife.agents.inventory.edit.ContainerEditor;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.PendingActionHints;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The container-correction flow (IN-g) through the agent's real HTTP surface: {@code /intent} asks to
 * confirm, {@code /resume} applies — the two turns of the shared ADR-0004 runner, with the
 * {@code box-edit-confirm} dispatch in {@code ResumeController} exercised for real.
 *
 * <p>The load-bearing assertions are the ones a move would otherwise get wrong: <b>turn 1 writes
 * nothing</b> (a store that moves a box before the owner says "да" is worse than no store), the box's
 * <b>printed identity survives</b> the edit (neither {@code code} nor {@code qrToken} is ever patched), a
 * status the model invented is <b>dropped rather than written</b>, and a decline leaves the box alone.
 *
 * <p>mcp-inventory answers through a <b>path dispatcher</b> rather than a response queue: five tests
 * sharing one queued server makes each test depend on how many calls its predecessors happened to make,
 * which is how a suite starts failing in a different order than it was written.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ContainerEditorTest {

    private static final UUID B07 = UUID.randomUUID();
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ZONE = UUID.randomUUID();

    static MockWebServer mcpInventory;
    static MockWebServer llmGateway;

    /** Bodies the agent POSTed MCP-side, by path — what a test asserts instead of a positional take. */
    static final Map<String, List<String>> POSTED = new ConcurrentHashMap<>();
    /** Every path the agent touched MCP-side, in order — for "nothing was written" assertions. */
    static final List<String> TOUCHED = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        mcpInventory = new MockWebServer();
        mcpInventory.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                TOUCHED.add(path);
                if ("POST".equals(request.getMethod())) {
                    POSTED.computeIfAbsent(key(path), k -> new CopyOnWriteArrayList<>())
                            .add(request.getBody().readUtf8());
                }
                try {
                    if (path.startsWith("/internal/containers/")) {
                        return json(MAPPER.writeValueAsString(view()));
                    }
                    if (path.startsWith("/internal/containers")) {
                        return "POST".equals(request.getMethod())
                                ? json(MAPPER.writeValueAsString(container("packed")))
                                : json(MAPPER.writeValueAsString(List.of(container("packed"))));
                    }
                    if (path.startsWith("/internal/zones")) {
                        return json(MAPPER.writeValueAsString(zone()));
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
        llmGateway.shutdown();
    }

    @BeforeEach
    void reset() {
        POSTED.clear();
        TOUCHED.clear();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("inventory-agent.mcp-inventory-url", () -> "http://localhost:" + mcpInventory.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    /** Turn 1: a move is confirmed, never applied — and the confirm carries the да/нет button hint. */
    @Test
    void aMoveAsksBeforeItTouchesTheStore() {
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"box-editor\"}"));
        llmGateway.enqueue(llm("{\"pick\":1,\"zone\":\"дача\"}"));

        IntentResponse resp = ask("коробка B-07 теперь на даче");

        assertThat(resp.text()).contains("B-07").contains("дача");
        JsonNode pending = resp.pendingAction();
        assertThat(pending).as("no confirm lock — the edit would apply unasked").isNotNull();
        assertThat(pending.path("flow").asString()).isEqualTo(ContainerEditor.FLOW);
        assertThat(pending.path("targetId").asString()).isEqualTo(B07.toString());
        assertThat(pending.path("zone").asString()).isEqualTo("дача");
        assertThat(pending.path(PendingActionHints.CONFIRM).asBoolean(false)).isTrue();

        // The candidate read happened; nothing was written.
        assertThat(TOUCHED).anyMatch(p -> p.startsWith("/internal/containers?"));
        assertThat(POSTED).as("turn 1 wrote to the store before the owner confirmed").isEmpty();
    }

    /** Turn 2: "да" upserts the zone and patches only that field — the printed identity is untouched. */
    @Test
    void anAffirmativeAppliesTheMoveAndKeepsThePrintedIdentity() {
        IntentResponse resp = resume("да", pending("zone", "дача"));

        assertThat(resp.text()).contains("B-07").contains("дача");
        // The lock is released, so the next message is routed normally again.
        assertThat(resp.pendingAction()).isNull();

        // The zone is filed under the CONTAINER's household, not the confirming message's envelope —
        // which is why the container is read back first.
        assertThat(TOUCHED).contains("/internal/containers/" + B07);
        JsonNode zoneBody = json.readTree(only("/internal/zones"));
        assertThat(zoneBody.path("name").asString()).isEqualTo("дача");
        assertThat(zoneBody.path("householdId").asString()).isEqualTo(HOUSEHOLD.toString());

        JsonNode patch = json.readTree(only("/internal/containers"));
        assertThat(patch.path("id").asString()).isEqualTo(B07.toString());
        assertThat(patch.path("zoneId").asString()).isEqualTo(ZONE.toString());
        // Nothing else was patched: a sticker already on the box has to keep resolving, and an
        // untouched field must not be blanked.
        assertThat(patch.hasNonNull("code")).as("code was re-sent — a rename would fight the label").isFalse();
        assertThat(patch.hasNonNull("qrToken")).isFalse();
        assertThat(patch.hasNonNull("status")).isFalse();
        assertThat(patch.hasNonNull("label")).as("the display label leaked into the patch as a rename").isFalse();
    }

    /** "распаковал" is the after-the-move verb: it flips status and touches nothing else. */
    @Test
    void unpackedFlipsOnlyTheStatus() {
        IntentResponse resp = resume("да", pending("status", "unpacked"));

        assertThat(resp.text()).contains("распакована");
        JsonNode patch = json.readTree(only("/internal/containers"));
        assertThat(patch.path("status").asString()).isEqualTo("unpacked");
        assertThat(patch.hasNonNull("zoneId")).isFalse();
        assertThat(patch.hasNonNull("label")).isFalse();
        // No zone upsert — "распаковал" says nothing about where the box stands.
        assertThat(POSTED).doesNotContainKey("/internal/zones");
    }

    /** A rename changes the name and nothing else — and it is NOT confused with the display label. */
    @Test
    void aRenameChangesOnlyTheName() {
        IntentResponse resp = resume("да", pending("newLabel", "зимние вещи"));

        assertThat(resp.text()).contains("зимние вещи");
        JsonNode patch = json.readTree(only("/internal/containers"));
        assertThat(patch.path("label").asString()).isEqualTo("зимние вещи");
        assertThat(patch.hasNonNull("zoneId")).isFalse();
        assertThat(patch.hasNonNull("status")).isFalse();
        // The printed identity is untouched, which is what makes a rename safe at all.
        assertThat(patch.hasNonNull("code")).isFalse();
        assertThat(patch.hasNonNull("qrToken")).isFalse();
    }

    /** A status the model invented is dropped — the store's state machine is not the model's to extend. */
    @Test
    void anInventedStatusIsNeverWritten() {
        IntentResponse resp = resume("да", pending("status", "почти собрана"));

        assertThat(resp.text()).contains("Нечего менять");
        assertThat(POSTED).isEmpty();
    }

    /** A decline leaves the box exactly as it was, and says so. */
    @Test
    void aDeclineChangesNothing() {
        IntentResponse resp = resume("нет", pending("zone", "дача"));

        assertThat(resp.text()).contains("без изменений");
        assertThat(resp.pendingAction()).isNull();
        assertThat(POSTED).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** The single body POSTed to a path — fails loudly when a flow wrote twice (or not at all). */
    private static String only(String path) {
        List<String> bodies = POSTED.get(path);
        assertThat(bodies).as("nothing was POSTed to %s (touched: %s)", path, TOUCHED).isNotNull();
        assertThat(bodies).as("more than one write to %s", path).hasSize(1);
        return bodies.get(0);
    }

    /** POST paths carry no query string in this flow; key by the path itself. */
    private static String key(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private IntentResponse ask(String text) {
        return http.post().uri("/agents/inventory/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message(text))
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .returnResult().getResponseBody();
    }

    private IntentResponse resume(String text, JsonNode pending) {
        return http.post().uri("/agents/inventory/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ResumeRequest(message(text), pending))
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .returnResult().getResponseBody();
    }

    private static NormalizedMessage message(String text) {
        return new NormalizedMessage(OWNER, HOUSEHOLD, MessageScope.PRIVATE, text, List.of(),
                "telegram", "1", Instant.now());
    }

    private JsonNode pending(String field, String value) {
        var node = json.createObjectNode();
        node.put("flow", ContainerEditor.FLOW);
        node.put("targetId", B07.toString());
        node.put("label", "B-07 «кухня — посуда»");
        node.put(field, value);
        return node;
    }

    private static ContainerDto container(String status) {
        return new ContainerDto(B07, HOUSEHOLD, OWNER, null, "B-07", "кухня — посуда", "box",
                "demo-b07", status, null, null, Instant.now(), null);
    }

    private static ContainerViewDto view() {
        return new ContainerViewDto(container("packed"), null, List.of());
    }

    private static StorageZoneDto zone() {
        return new StorageZoneDto(ZONE, HOUSEHOLD, OWNER, "дача", "dacha", null, null, Instant.now());
    }

    private MockResponse llm(String content) {
        return json(json.writeValueAsString(
                new LlmChatResponse("mock-large", content, "stop", new LlmUsage(20, 10, 30))));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
