package dev.fedorov.ailife.agents.inventory;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.note.NoteDto;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the "где лежит X" flow through the agent's HTTP surface: the router picks
 * {@code item-finder}, one LLM turn distils the thing out of the question, and the search answers
 * with the <b>place</b>. MockWebServers stand in for llm-gateway, mcp-inventory and memory-service.
 *
 * <p>Two sources answer it (IN-e2): the literal trigram search over item titles and a semantic recall
 * over the second brain, whose note back-pointer resolves to the item. What these tests pin down is that
 * the recall <b>adds</b> reach without becoming a dependency — each source soft-fails alone, a thing found
 * by both is listed once, and a note outliving its item is silently dropped rather than answered as a
 * location.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ItemFinderTest {

    static MockWebServer mcpInventory;
    static MockWebServer llmGateway;
    static MockWebServer memoryService;

    /**
     * memory-service is driven by a path dispatcher, not the shared queue: the recall is a side call the
     * finder makes in parallel with the search, so its answers must not depend on enqueue order.
     * {@link #recallJson} / {@link #noteJson} are what the current test wants it to say; {@link #memoryDown}
     * makes it fail.
     */
    static volatile String recallJson = "[]";
    static volatile String noteJson = null;
    static volatile boolean memoryDown = false;

    @BeforeAll
    static void start() throws Exception {
        mcpInventory = new MockWebServer();
        llmGateway = new MockWebServer();
        memoryService = new MockWebServer();
        memoryService.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (memoryDown) {
                    return new MockResponse().setResponseCode(503);
                }
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/v1/memories/recall")) {
                    return jsonResponse(recallJson);
                }
                if (path.startsWith("/v1/notes/")) {
                    return noteJson == null
                            ? new MockResponse().setResponseCode(404) : jsonResponse(noteJson);
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        mcpInventory.start();
        llmGateway.start();
        memoryService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpInventory.shutdown();
        llmGateway.shutdown();
        memoryService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("inventory-agent.mcp-inventory-url", () -> "http://localhost:" + mcpInventory.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("inventory-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    /** Recall is the bonus source: unless a test arms it, it answers "nothing" and stays out of the way. */
    @BeforeEach
    void noRecallByDefault() throws Exception {
        recallJson = "[]";
        noteJson = null;
        memoryDown = false;
        // Two sources now hit mcp-inventory, so leftovers from a previous test would offset any
        // positional take() — start each test with an empty recorded queue.
        while (mcpInventory.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // drain
        }
    }

    @Test
    void answersWithTheContainerAndItsZone() throws Exception {
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"ёлочные игрушки\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(List.of(
                hit("ёлочные игрушки", "B-07", "Новый год", "кладовка")))));

        IntentResponse response = ask("где лежат ёлочные игрушки?");

        assertThat(response.text())
                .contains("ёлочные игрушки")
                .contains("B-07")
                .contains("Новый год")
                .contains("кладовка");

        // The question's interrogatives must not reach the search — the stored names came from photo
        // captions, so "где лежат" would only dilute the match.
        RecordedRequest search = take(mcpInventory);
        assertThat(search.getPath()).startsWith("/internal/items/search");
        assertThat(search.getPath()).doesNotContain("%D0%B3%D0%B4%D0%B5"); // "где"
    }

    @Test
    void saysSoPlainlyWhenNothingMatches() throws Exception {
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"дрель\"}"));
        mcpInventory.enqueue(jsonResponse("[]"));

        IntentResponse response = ask("куда я убрал дрель");

        assertThat(response.text()).contains("Не нашёл").contains("дрель");
        // Never invent a location for a thing that was never packed.
        assertThat(response.text()).doesNotContain("коробка B-");
    }

    @Test
    void namesTheContainerEvenWhenItHasNoZoneYet() throws Exception {
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"дрель\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(List.of(
                hit("дрель", "B-03", "инструменты", null)))));

        IntentResponse response = ask("в какой коробке дрель");

        assertThat(response.text()).contains("B-03").contains("зона не указана");
    }

    // ── IN-e2: the semantic half ─────────────────────────────────────────────────────────────────

    /**
     * The vocabulary mismatch this slice exists for: the thing is titled as the vision model saw it
     * ("гриль-решётка чугунная") and asked for as the owner thinks of it ("та штука для гриля"). The
     * trigram search finds nothing; the recall resolves the note → its container → the item, and the
     * reply names the place exactly as a literal hit would.
     */
    @Test
    void recallAnswersWhenTheWordsDoNotMatchTheTitle() throws Exception {
        UUID containerId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        recallJson = json.writeValueAsString(List.of(noteHit(UUID.randomUUID())));
        noteJson = json.writeValueAsString(itemNote(itemId, containerId));
        mcpInventory.enqueue(jsonResponse("[]"));                       // trigram: nothing matches
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(     // the back-pointer's container
                view(containerId, "B-09", "дача — мангал", "гараж", itemId, "гриль-решётка чугунная"))));
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"штука для гриля\"}"));

        IntentResponse response = ask("где та штука для гриля");

        assertThat(response.text())
                .contains("гриль-решётка чугунная")
                .contains("B-09")
                .contains("гараж");
        assertThat(response.text()).doesNotContain("Не нашёл");
    }

    /** A memory outage may cost the semantic reach, never the literal answer. */
    @Test
    void literalHitsSurviveMemoryBeingDown() throws Exception {
        memoryDown = true;
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"дрель\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(List.of(
                hit("дрель", "B-03", "инструменты", "гараж")))));

        IntentResponse response = ask("где дрель");

        assertThat(response.text()).contains("дрель").contains("B-03").contains("гараж");
    }

    /** Both sources found the same thing — it is one thing, so it is listed once. */
    @Test
    void athingFoundByBothSourcesIsListedOnce() throws Exception {
        UUID containerId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        int readsBefore = mcpInventory.getRequestCount();
        recallJson = json.writeValueAsString(List.of(noteHit(UUID.randomUUID())));
        noteJson = json.writeValueAsString(itemNote(itemId, containerId));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(List.of(
                new ItemLocationDto(
                        new ItemDto(itemId, containerId, "media-1", "ёлочная гирлянда", null, null, 1,
                                Instant.now()),
                        container(containerId, "B-07", "Новый год", null), null)))));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(
                view(containerId, "B-07", "Новый год", "кладовка", itemId, "ёлочная гирлянда"))));
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"гирлянда\"}"));

        IntentResponse response = ask("где гирлянда");

        assertThat(response.text()).contains("ёлочная гирлянда");
        assertThat(response.text()).doesNotContain("Также похоже");   // one thing, not two
        // Both sources really ran (the search + the recalled note's container) — otherwise "listed once"
        // would pass trivially on a recall that found nothing.
        assertThat(mcpInventory.getRequestCount() - readsBefore).isEqualTo(2);
    }

    /** The note outlived its item (it was taken out of the box) — a stale index must not answer. */
    @Test
    void aNoteWhoseItemIsGoneIsSkipped() throws Exception {
        UUID containerId = UUID.randomUUID();
        UUID goneItemId = UUID.randomUUID();
        int readsBefore = mcpInventory.getRequestCount();
        recallJson = json.writeValueAsString(List.of(noteHit(UUID.randomUUID())));
        noteJson = json.writeValueAsString(itemNote(goneItemId, containerId));
        mcpInventory.enqueue(jsonResponse("[]"));
        // The container is still there, but the thing is no longer among its items.
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(new ContainerViewDto(
                container(containerId, "B-07", "Новый год", null), null, List.of()))));
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"гирлянда\"}"));

        IntentResponse response = ask("где гирлянда");

        assertThat(response.text()).contains("Не нашёл");
        assertThat(response.text()).doesNotContain("B-07");
        // The recall did resolve the note and read the box — it just found nothing in it to answer with.
        assertThat(mcpInventory.getRequestCount() - readsBefore).isEqualTo(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A recall hit as memory-service returns it for a note's seed: {@code source=note, kind=note}. */
    private RecallMemoryHit noteHit(UUID noteId) {
        ObjectNode meta = json.createObjectNode();
        meta.put("kind", "note");
        meta.put("refId", noteId.toString());
        return new RecallMemoryHit(new MemoryDto(UUID.randomUUID(), UUID.randomUUID(), null, null,
                "note", "гриль-решётка чугунная", meta, Instant.now()), 0.12);
    }

    /** The note an inventory item seeds: its frontmatter back-points at the item and its container. */
    private NoteDto itemNote(UUID itemId, UUID containerId) {
        ObjectNode fm = json.createObjectNode();
        fm.put("kind", "item");
        fm.put("refId", itemId.toString());
        fm.put("containerId", containerId.toString());
        return new NoteDto(UUID.randomUUID(), UUID.randomUUID(), null, "гриль-решётка чугунная",
                "reference", List.of("item"), "inventory-agent", null, "гриль-решётка чугунная", fm,
                Instant.now(), null);
    }

    private static ContainerViewDto view(UUID containerId, String code, String label, String zoneName,
                                         UUID itemId, String itemTitle) {
        UUID zoneId = zoneName == null ? null : UUID.randomUUID();
        return new ContainerViewDto(
                container(containerId, code, label, zoneId),
                zoneName == null ? null : new StorageZoneDto(zoneId, UUID.randomUUID(), null, zoneName,
                        "closet", null, null, Instant.now()),
                List.of(new ItemDto(itemId, containerId, "media-1", itemTitle, null, null, 1,
                        Instant.now())));
    }

    private static ContainerDto container(UUID id, String code, String label, UUID zoneId) {
        return new ContainerDto(id, UUID.randomUUID(), null, zoneId, code, label, "box",
                "abcdef0123456789", "packed", null, null, Instant.now(), null);
    }

    private IntentResponse ask(String text) {
        return http.post().uri("/agents/inventory/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(),
                        MessageScope.PRIVATE, text, List.of(), "telegram", "1", Instant.now()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .returnResult().getResponseBody();
    }

    private static ItemLocationDto hit(String title, String code, String label, String zoneName) {
        UUID containerId = UUID.randomUUID();
        UUID zoneId = zoneName == null ? null : UUID.randomUUID();
        return new ItemLocationDto(
                new ItemDto(UUID.randomUUID(), containerId, "media-1", title, null, null, 1,
                        Instant.now()),
                new ContainerDto(containerId, UUID.randomUUID(), null, zoneId, code, label, "box",
                        "abcdef0123456789", "packed", null, null, Instant.now(), null),
                zoneName == null ? null : new StorageZoneDto(zoneId, UUID.randomUUID(), null,
                        zoneName, "closet", null, null, Instant.now()));
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
