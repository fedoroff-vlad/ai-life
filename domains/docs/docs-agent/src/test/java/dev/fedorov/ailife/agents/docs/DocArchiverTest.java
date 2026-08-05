package dev.fedorov.ailife.agents.docs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.OcrResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the doc-archiver ingest flow (D-c) through the agent's HTTP surface ({@code POST
 * /agents/docs/intent}): a message carrying a document photo → mcp-media-processing OCRs it → llm-gateway
 * extracts the metadata via the {@code doc-archiver} SKILL → mcp-docs archives it. MockWebServers stand
 * in for mcp-media-processing, llm-gateway, and mcp-docs. A plain-text message (no photo) falls through
 * to the chat fallback without touching OCR or the archive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class DocArchiverTest {

    static MockWebServer mcpDocs;
    static MockWebServer mcpMediaProcessing;
    static MockWebServer llmGateway;
    static MockWebServer profileService;

    @BeforeAll
    static void start() throws Exception {
        mcpDocs = new MockWebServer();
        mcpMediaProcessing = new MockWebServer();
        llmGateway = new MockWebServer();
        profileService = new MockWebServer();
        mcpDocs.start();
        mcpMediaProcessing.start();
        llmGateway.start();
        profileService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpDocs.shutdown();
        mcpMediaProcessing.shutdown();
        llmGateway.shutdown();
        profileService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("docs-agent.mcp-docs-url", () -> "http://localhost:" + mcpDocs.getPort());
        r.add("docs-agent.mcp-media-processing-url", () -> "http://localhost:" + mcpMediaProcessing.getPort());
        r.add("docs-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        // Item 8: the SharingResolver now consults/records the learned-decision tally on memory-service.
        // These cases exercise the no-history path (static policy), so point it at a fast-fail address — the
        // learned lookup soft-fails to empty (→ static default) and any record is swallowed. Behaviour is
        // exactly as before item 8.
        r.add("docs-agent.memory-service-url", () -> "http://127.0.0.1:1");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void documentPhotoIsOcrdExtractedAndArchived() throws Exception {
        UUID envelopeHh = UUID.randomUUID();  // a rental contract is a household doc → must re-route to shared
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mcpMediaProcessing.enqueue(jsonResponse(json.writeValueAsString(
                new OcrResult("ДОГОВОР АРЕНДЫ квартиры\nАрендодатель: ООО Ромашка\n15.01.2026", "ru", 0.9))));
        String draftJson = "{\"docType\":\"contract\",\"title\":\"Договор аренды\",\"party\":\"ООО Ромашка\","
                + "\"docDate\":\"2026-01-15\",\"tags\":[\"аренда\"]}";
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", draftJson, "stop", new LlmUsage(120, 30, 150)))));
        // household-routing: the user's personal + one shared household (ADR-0002 slice 7 write path).
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));
        mcpDocs.enqueue(jsonResponse(json.writeValueAsString(new DocumentDto(
                UUID.randomUUID(), sharedHh, userId, "media-1", "contract", "Договор аренды",
                "ООО Ромашка", LocalDate.of(2026, 1, 15), null, null,
                "ДОГОВОР АРЕНДЫ квартиры", json.readTree("[\"аренда\"]"), Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, envelopeHh, MessageScope.PRIVATE,
                "вот договор аренды, сохрани",
                List.of(new Attachment("image", "image/jpeg", "media-1", null)),
                "telegram", "80", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Заархивировал").contains("Договор аренды");

        // OCR ran over the media id.
        RecordedRequest ocrReq = mcpMediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(ocrReq.getPath()).isEqualTo("/internal/ocr");
        assertThat(ocrReq.getBody().readUtf8()).contains("media-1");

        // The extract went through llm-gateway with the SKILL system prompt + the OCR text + the note.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String llmBody = llmReq.getBody().readUtf8();
        assertThat(llmBody).contains("strict JSON").contains("ДОГОВОР АРЕНДЫ").contains("сохрани");

        // The household-routing split was read for the acting user.
        RecordedRequest routingReq = profileService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(routingReq.getPath()).contains("/household-routing").contains(userId.toString());

        // The document was archived with the blob id, extracted metadata, and full OCR text — and under
        // the SHARED household (a contract → shared per DocsSharingPolicy), not the envelope one.
        RecordedRequest saveReq = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        assertThat(saveReq.getPath()).isEqualTo("/internal/documents");
        JsonNode body = json.readTree(saveReq.getBody().readUtf8());
        assertThat(body.path("householdId").asString()).isEqualTo(sharedHh.toString());
        assertThat(body.path("ownerId").asString()).isEqualTo(userId.toString());
        assertThat(body.path("mediaId").asString()).isEqualTo("media-1");
        assertThat(body.path("docType").asString()).isEqualTo("contract");
        assertThat(body.path("title").asString()).isEqualTo("Договор аренды");
        assertThat(body.path("party").asString()).isEqualTo("ООО Ромашка");
        assertThat(body.path("docDate").asString()).isEqualTo("2026-01-15");
        assertThat(body.path("ocrText").asString()).contains("ДОГОВОР АРЕНДЫ");
        assertThat(body.path("tags").get(0).asString()).isEqualTo("аренда");
    }

    @Test
    void receiptPhotoStaysPersonalEvenWithSharedHousehold() throws Exception {
        UUID envelopeHh = UUID.randomUUID();
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();  // a family household exists, but a receipt is a personal paper
        UUID userId = UUID.randomUUID();

        mcpMediaProcessing.enqueue(jsonResponse(json.writeValueAsString(
                new OcrResult("ПЯТЁРОЧКА\nИТОГО 1234.56 RUB", "ru", 0.8))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"docType\":\"receipt\",\"title\":\"Чек Пятёрочка\",\"party\":\"Пятёрочка\",\"amount\":1234.56,\"currency\":\"RUB\"}",
                "stop", new LlmUsage(80, 20, 100)))));
        // household-routing: a shared household is available, but a receipt (PRIVATE) still routes personal.
        profileService.enqueue(jsonResponse("{\"personalHouseholdId\":\"" + personalHh
                + "\",\"sharedHouseholdIds\":[\"" + sharedHh + "\"]}"));
        mcpDocs.enqueue(jsonResponse(json.writeValueAsString(new DocumentDto(
                UUID.randomUUID(), personalHh, userId, "media-2", "receipt", "Чек Пятёрочка",
                "Пятёрочка", null, new BigDecimal("1234.56"), "RUB", "ПЯТЁРОЧКА ИТОГО 1234.56 RUB",
                null, Instant.now()))));

        NormalizedMessage msg = new NormalizedMessage(userId, envelopeHh, MessageScope.PRIVATE, null,
                List.of(new Attachment("image", "image/jpeg", "media-2", null)),
                "telegram", "81", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Заархивировал");

        mcpMediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        profileService.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest saveReq = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        JsonNode body = json.readTree(saveReq.getBody().readUtf8());
        // a receipt is PRIVATE → the personal household, never the shared one.
        assertThat(body.path("householdId").asString()).isEqualTo(personalHh.toString());
        assertThat(body.path("docType").asString()).isEqualTo("receipt");
        assertThat(new BigDecimal(body.path("amount").asString())).isEqualByComparingTo("1234.56");
        assertThat(body.path("currency").asString()).isEqualTo("RUB");
    }

    @Test
    void plainTextFallsThroughToChatWithoutOcrOrArchive() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Пришлите фото документа, и я сохраню его в архив.", "stop",
                new LlmUsage(20, 12, 32)))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что ты умеешь?", List.of(), "telegram", "82", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("архив");

        // Chat used the LLM, but neither OCR nor the archive were touched.
        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(mcpMediaProcessing.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mcpDocs.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/docs/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
