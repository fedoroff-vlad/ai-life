package dev.fedorov.ailife.agents.inventory;

import dev.fedorov.ailife.agents.inventory.edit.ItemRemover;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.PendingActionHints;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Taking a thing out of its box (IN-g2) through the agent's real HTTP surface — the two turns of the
 * shared ADR-0004 runner plus the {@code item-remove-confirm} dispatch in {@code ResumeController}.
 *
 * <p>Deletion is the one irreversible act in this domain (an item's row carries the only reference to its
 * photo), so the assertions are about restraint: turn 1 **deletes nothing**, the candidate pool is the
 * *search* rather than the whole store (a household after a move holds hundreds of things), a decline
 * leaves the thing where it is, and an already-gone item is not reported as a failure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ItemRemoverTest {

    private static final UUID KETTLE = UUID.randomUUID();
    private static final UUID GARLAND = UUID.randomUUID();
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();

    static MockWebServer mcpInventory;
    static MockWebServer llmGateway;

    /** Every MCP request the agent made, as "METHOD path" — deletes are what a test must not see early. */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        llmGateway.start();
        mcpInventory = new MockWebServer();
        mcpInventory.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                CALLS.add(request.getMethod() + " " + path);
                if ("DELETE".equals(request.getMethod())) {
                    return new MockResponse().setResponseCode(204);
                }
                try {
                    if (path.startsWith("/internal/items/search")) {
                        return json(MAPPER.writeValueAsString(List.of(hit(KETTLE, "старый чайник", "B-07"),
                                hit(GARLAND, "ёлочная гирлянда", "B-03"))));
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
        CALLS.clear();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("inventory-agent.mcp-inventory-url", () -> "http://localhost:" + mcpInventory.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    /** Turn 1: the search is the candidate pool, the confirm names the thing AND its box, nothing is deleted. */
    @Test
    void aRemovalAsksBeforeDeletingAnything() {
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-remover\"}"));   // router
        llmGateway.enqueue(llm("{\"query\":\"старый чайник\"}"));                      // phrase distil
        llmGateway.enqueue(llm("{\"pick\":1}"));                                       // which candidate

        IntentResponse resp = ask("убери из коробки старый чайник");

        assertThat(resp.text()).contains("чайник").contains("B-07");
        JsonNode pending = resp.pendingAction();
        assertThat(pending).as("no confirm lock — the item would be deleted unasked").isNotNull();
        assertThat(pending.path("flow").asString()).isEqualTo(ItemRemover.FLOW);
        assertThat(pending.path("targetId").asString()).isEqualTo(KETTLE.toString());
        assertThat(pending.path(PendingActionHints.CONFIRM).asBoolean(false)).isTrue();

        // The pool came from the SEARCH (on the distilled phrase), not from the whole store.
        assertThat(CALLS).anyMatch(c -> c.startsWith("GET /internal/items/search")
                && c.contains("query=%D1%81%D1%82%D0%B0%D1%80%D1%8B%D0%B9")); // "старый…" url-encoded
        assertThat(CALLS).as("turn 1 deleted something").noneMatch(c -> c.startsWith("DELETE"));
    }

    /** Turn 2: "да" deletes exactly the confirmed item, and the lock is released. */
    @Test
    void anAffirmativeDeletesTheConfirmedItem() {
        IntentResponse resp = resume("да", pending(KETTLE, "старый чайник из B-07"));

        assertThat(resp.text()).contains("чайник");
        assertThat(resp.pendingAction()).isNull();
        assertThat(CALLS).containsExactly("DELETE /internal/items/" + KETTLE);
    }

    /** A decline leaves the thing in its box — the reply says so, and nothing is touched. */
    @Test
    void aDeclineKeepsTheItem() {
        IntentResponse resp = resume("нет", pending(GARLAND, "ёлочная гирлянда из B-03"));

        assertThat(resp.pendingAction()).isNull();
        assertThat(CALLS).as("a declined removal still deleted").isEmpty();
    }

    /** An item already gone is not a failure: the owner asked for it not to be there, and it is not. */
    @Test
    void anAlreadyDeletedItemIsNotAnError() {
        IntentResponse resp = resume("да", pending(UUID.randomUUID(), "старый чайник"));

        assertThat(resp.text()).doesNotContain("Не смог");
        assertThat(CALLS).hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

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

    private JsonNode pending(UUID targetId, String label) {
        var node = json.createObjectNode();
        node.put("flow", ItemRemover.FLOW);
        node.put("targetId", targetId.toString());
        node.put("label", label);
        return node;
    }

    private static ItemLocationDto hit(UUID itemId, String title, String code) {
        UUID containerId = UUID.randomUUID();
        return new ItemLocationDto(
                new ItemDto(itemId, containerId, "media-" + code, title, null, null, 1, Instant.now()),
                new ContainerDto(containerId, HOUSEHOLD, OWNER, null, code, null, "box",
                        "demo-" + code.toLowerCase(), "packed", null, null, Instant.now(), null),
                new StorageZoneDto(UUID.randomUUID(), HOUSEHOLD, OWNER, "кладовка", "closet", null,
                        null, Instant.now()));
    }

    private MockResponse llm(String content) {
        return json(json.writeValueAsString(
                new LlmChatResponse("mock-large", content, "stop", new LlmUsage(20, 10, 30))));
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
