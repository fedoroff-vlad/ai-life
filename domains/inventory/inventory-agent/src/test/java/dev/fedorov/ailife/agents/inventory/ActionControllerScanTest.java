package dev.fedorov.ailife.agents.inventory;

import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scan path's agent half (IN-f1): {@code POST /agents/inventory/actions/show_container} resolves a
 * printed label's token to that container's card.
 *
 * <p>A scan arrives as an inter-agent action rather than a message on purpose — a sticker carries no
 * sentence, so nothing is classified and no LLM turn stands between the camera and the answer. The
 * load-bearing assertions are therefore that the lookup is <b>by token alone</b> (which is what lets
 * whoever is unpacking get an answer) and that an unknown token is <b>answered, never guessed</b>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ActionControllerScanTest {

    // A low-entropy stand-in for an opaque qr_token (a hex-looking literal beside "token" trips gitleaks).
    private static final String LABEL_ID = "demo-b07";
    private static final String CODE = "B-07";

    static MockWebServer mcpInventory;
    static MockWebServer mediaService;

    @BeforeAll
    static void start() throws Exception {
        mcpInventory = new MockWebServer();
        mediaService = new MockWebServer();
        mcpInventory.start();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpInventory.shutdown();
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("inventory-agent.mcp-inventory-url", () -> "http://localhost:" + mcpInventory.getPort());
        r.add("inventory-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("inventory-agent.public-media-base-url", () -> "https://media.example");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    /** Scenario: camera scan — the token resolves to the container's card, with no LLM turn involved. */
    @Test
    void aScannedTokenReturnsThatContainersCard() throws Exception {
        UUID cardMedia = UUID.randomUUID();
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(view())));
        mediaService.enqueue(jsonResponse(json.writeValueAsString(stored(cardMedia))));

        AgentActionResult result = scan(LABEL_ID);

        assertThat(result).isNotNull();
        assertThat(result.ok()).isTrue();
        assertThat(result.result().path("message").asString())
                .contains(CODE)
                .contains("кладовка")
                .contains("https://media.example/v1/media/" + cardMedia);
        assertThat(result.result().path("cardUrl").asString())
                .isEqualTo("https://media.example/v1/media/" + cardMedia);

        // The lookup is by the printed token alone — no household, no code: that is what makes a
        // sticker scannable by whoever is holding the box.
        RecordedRequest lookup = take(mcpInventory);
        assertThat(lookup.getPath()).isEqualTo("/internal/containers/by-token/" + LABEL_ID);
        // The card names the contents, so a scan answers "what is in this box" in one hop.
        assertThat(take(mediaService).getBody().readUtf8()).contains("ёлочная гирлянда");
    }

    /** A sticker outlives the row it points at — say so instead of opening a neighbouring box. */
    @Test
    void anUnknownTokenIsAnsweredNotGuessed() throws Exception {
        int storedBefore = mediaService.getRequestCount();
        mcpInventory.enqueue(new MockResponse().setResponseCode(404));

        AgentActionResult result = scan("no-such-label");

        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Не нашёл коробку");
        assertThat(mediaService.getRequestCount()).isEqualTo(storedBefore);
        take(mcpInventory);
    }

    /** A malformed dispatch is a structured refusal, not a 500 — the hub relays it verbatim. */
    @Test
    void aMissingTokenIsRejectedStructurally() {
        AgentActionResult result = post("show_container", json.createObjectNode());

        assertThat(result).isNotNull();
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("qrToken");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private AgentActionResult scan(String qrToken) {
        ObjectNode args = json.createObjectNode();
        args.put("qrToken", qrToken);
        return post("show_container", args);
    }

    private AgentActionResult post(String action, ObjectNode args) {
        return http.post().uri("/agents/inventory/actions/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AgentActionRequest("inventory", action, UUID.randomUUID(),
                        UUID.randomUUID(), "gateway", args))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AgentActionResult.class)
                .returnResult().getResponseBody();
    }

    private static final UUID CONTAINER_ID = UUID.randomUUID();

    private static ContainerViewDto view() {
        ContainerDto container = new ContainerDto(CONTAINER_ID, UUID.randomUUID(), null,
                UUID.randomUUID(), CODE, "кухня — посуда", "box", LABEL_ID, "packed", null, null,
                Instant.now(), null);
        StorageZoneDto zone = new StorageZoneDto(UUID.randomUUID(), UUID.randomUUID(), null,
                "кладовка", "closet", null, null, Instant.now());
        return new ContainerViewDto(container, zone, List.of(
                new ItemDto(UUID.randomUUID(), CONTAINER_ID, "media-1", "ёлочная гирлянда", null,
                        null, 1, Instant.now())));
    }

    private static MediaObjectDto stored(UUID id) {
        return new MediaObjectDto(id, UUID.randomUUID(), null, "file", "text/html", 2048, null,
                "inventory", Instant.now());
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    private static RecordedRequest take(MockWebServer server) throws Exception {
        return server.takeRequest(5, TimeUnit.SECONDS);
    }
}
