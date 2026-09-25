package dev.fedorov.ailife.agents.inventory;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import dev.fedorov.ailife.agents.inventory.label.BoxLabelImage;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
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

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises a container's two deliverables (IN-d) through the agent's HTTP surface: the printable QR
 * label and the doc-render card. MockWebServers stand in for llm-gateway, mcp-inventory and
 * media-service.
 *
 * <p>The load-bearing assertion is that the <b>label carries only an id</b>: it is byte-identical
 * before and after the box is repacked, and what it encodes is the {@code box_<token>} deep link —
 * that is what makes editing a container safe once a sticker is already on it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class BoxLabelerTest {

    // A deliberately low-entropy stand-in: a real qr_token is opaque, but a random-looking hex
    // literal beside the word "token" trips the secret scan (gitleaks generic-api-key).
    private static final String LABEL_ID = "demo-b07";
    private static final String CODE = "B-07";

    static MockWebServer mcpInventory;
    static MockWebServer llmGateway;
    static MockWebServer mediaService;

    @BeforeAll
    static void start() throws Exception {
        mcpInventory = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaService = new MockWebServer();
        mcpInventory.start();
        llmGateway.start();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpInventory.shutdown();
        llmGateway.shutdown();
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("inventory-agent.mcp-inventory-url", () -> "http://localhost:" + mcpInventory.getPort());
        r.add("inventory-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("inventory-agent.public-media-base-url", () -> "https://media.example");
        r.add("inventory-agent.telegram-bot-username", () -> "ai_life_bot");
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    /** Scenario: label is content-independent — the sticker survives repacking the box. */
    @Test
    void theLabelEncodesTheDeepLinkAndNotTheContents() throws Exception {
        UUID labelMedia = UUID.randomUUID();
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"box-label\"}"));
        llmGateway.enqueue(llm("{\"container\":\"B-07\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(List.of(container()))));
        mediaService.enqueue(jsonResponse(json.writeValueAsString(stored(labelMedia, "image/png"))));

        IntentResponse response = ask("распечатай этикетку на B-07");

        assertThat(response.text()).contains(CODE).contains("https://media.example/v1/media/" + labelMedia);

        // The uploaded bytes are a scannable QR of the deep link — an id, never the contents.
        take(mcpInventory);
        RecordedRequest upload = take(mediaService);
        assertThat(upload.getPath()).isEqualTo("/v1/media");
        String body = upload.getBody().readUtf8();
        assertThat(body).contains("box-" + CODE + "-label.png").contains("image/png");
        assertThat(body).doesNotContain("посуда"); // the container's label is not in the image payload
    }

    /** The same token always renders the same image — re-issuing after a repack changes nothing. */
    @Test
    void theQrIsDeterministicAndDecodesBackToTheBoxDeepLink() throws Exception {
        String payload = BoxLabelImage.deepLink("ai_life_bot", LABEL_ID);
        assertThat(payload).isEqualTo("https://t.me/ai_life_bot?start=box_" + LABEL_ID);

        byte[] first = BoxLabelImage.png(payload);
        byte[] second = BoxLabelImage.png(payload);
        assertThat(first).isEqualTo(second);
        assertThat(decode(first)).isEqualTo(payload);
    }

    /** Scenario: card render — the board lists every item with its photo. */
    @Test
    void theCardListsTheContentsWithTheirPhotos() throws Exception {
        UUID cardMedia = UUID.randomUUID();
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"box-card\"}"));
        llmGateway.enqueue(llm("{\"container\":\"B-07\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(List.of(container()))));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(view())));
        mediaService.enqueue(jsonResponse(json.writeValueAsString(stored(cardMedia, "text/html"))));

        IntentResponse response = ask("что в коробке B-07");

        assertThat(response.text())
                .contains(CODE)
                .contains("упакована")
                .contains("2 предмета")
                .contains("https://media.example/v1/media/" + cardMedia);

        take(mcpInventory);
        take(mcpInventory);
        String board = take(mediaService).getBody().readUtf8();
        assertThat(board).contains("ёлочная гирлянда").contains("Вещь без названия");
        assertThat(board).contains("https://media.example/v1/media/media-1"); // the item's own photo
    }

    /** An unresolvable box is answered, not guessed — a wrong guess prints the wrong sticker. */
    @Test
    void asksAgainWhenNoContainerMatches() throws Exception {
        int storedBefore = mediaService.getRequestCount();
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"box-label\"}"));
        llmGateway.enqueue(llm("{\"container\":\"B-99\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(List.of(container()))));

        IntentResponse response = ask("распечатай этикетку на B-99");

        assertThat(response.text()).contains("Не нашёл коробку").contains("B-99");
        // Nothing was rendered or stored — no sticker for a box we could not identify.
        assertThat(mediaService.getRequestCount()).isEqualTo(storedBefore);
        take(mcpInventory);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

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

    private static final UUID CONTAINER_ID = UUID.randomUUID();

    private static ContainerDto container() {
        return new ContainerDto(CONTAINER_ID, UUID.randomUUID(), null, UUID.randomUUID(),
                CODE, "кухня — посуда", "box", LABEL_ID, "packed", null, null, Instant.now(), null);
    }

    private static ContainerViewDto view() {
        return new ContainerViewDto(container(), null, List.of(
                new ItemDto(UUID.randomUUID(), CONTAINER_ID, "media-1", "ёлочная гирлянда", null,
                        null, 1, Instant.now()),
                new ItemDto(UUID.randomUUID(), CONTAINER_ID, null, null, null, null, null,
                        Instant.now())));
    }

    private static MediaObjectDto stored(UUID id, String mime) {
        return new MediaObjectDto(id, UUID.randomUUID(), null, "file", mime, 1024, null,
                "inventory", Instant.now());
    }

    private static String decode(byte[] png) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(png));
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return new MultiFormatReader().decode(bitmap).getText();
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
