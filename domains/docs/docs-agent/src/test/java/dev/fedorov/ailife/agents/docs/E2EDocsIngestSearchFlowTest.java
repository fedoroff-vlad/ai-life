package dev.fedorov.ailife.agents.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.OcrResult;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-e end-to-end closer for the docs-agent (issue #188). Proves the full ingest→search chain over
 * <b>real HTTP boundaries</b> in ONE real docs-agent Spring context, with MockWebServers standing in
 * for every hop (mcp-media-processing, llm-gateway, mcp-docs, memory-service), asserting the
 * {@code libs/contracts} DTOs survive serialisation each way:
 *
 * <ol>
 *   <li><b>Ingest</b> — a document photo → {@code OcrResult} → LLM metadata extract →
 *       {@code SaveDocumentInput}/{@code DocumentDto} archived → the OCR text seeded to memory-service
 *       ({@code WriteMemoryRequest}) carrying a {@code {kind:document, refId}} back-pointer.</li>
 *   <li><b>Search</b> — a "find my X" cue where the trigram search returns <em>nothing</em>, so the
 *       document is recovered purely by the D-e semantic path: memory-service recall
 *       ({@code RecallMemoryHit}) → the {@code refId} resolves to {@code getDocument} → the reply lists
 *       the same document ingested in step 1, with its open link.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class E2EDocsIngestSearchFlowTest {

    static MockWebServer mcpDocs;
    static MockWebServer mcpMediaProcessing;
    static MockWebServer llmGateway;
    static MockWebServer memoryService;

    @BeforeAll
    static void start() throws Exception {
        mcpDocs = new MockWebServer();
        mcpMediaProcessing = new MockWebServer();
        llmGateway = new MockWebServer();
        memoryService = new MockWebServer();
        mcpDocs.start();
        mcpMediaProcessing.start();
        llmGateway.start();
        memoryService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpDocs.shutdown();
        mcpMediaProcessing.shutdown();
        llmGateway.shutdown();
        memoryService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("docs-agent.mcp-docs-url", () -> "http://localhost:" + mcpDocs.getPort());
        r.add("docs-agent.mcp-media-processing-url", () -> "http://localhost:" + mcpMediaProcessing.getPort());
        r.add("docs-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("docs-agent.public-media-base-url", () -> "https://media.example");
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void ingestedDocumentIsRecoveredBySemanticRecall() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();               // threaded ingest → seed → recall → getDocument
        String mediaId = "media-de-1";
        String ocrText = "ДОГОВОР АРЕНДЫ квартиры\nАрендодатель: ООО Ромашка\n15.01.2026";

        // ---- Phase 1: ingest ------------------------------------------------------------------
        mcpMediaProcessing.enqueue(jsonResponse(json.writeValueAsString(new OcrResult(ocrText, "ru", 0.9))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large",
                "{\"docType\":\"contract\",\"title\":\"Договор аренды\",\"party\":\"ООО Ромашка\",\"docDate\":\"2026-01-15\"}",
                "stop", new LlmUsage(120, 30, 150)))));
        DocumentDto archived = new DocumentDto(docId, householdId, userId, mediaId, "contract",
                "Договор аренды", "ООО Ромашка", LocalDate.of(2026, 1, 15), null, null,
                ocrText, null, Instant.now());
        mcpDocs.enqueue(jsonResponse(json.writeValueAsString(archived)));
        memoryService.enqueue(new MockResponse().setResponseCode(200));   // the D-e semantic seed

        NormalizedMessage ingest = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "вот договор аренды, сохрани",
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "de-1", Instant.now());

        IntentResponse ingestResp = post(ingest);
        assertThat(ingestResp).isNotNull();
        assertThat(ingestResp.text()).contains("Заархивировал").contains("Договор аренды");

        assertThat(mcpMediaProcessing.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/internal/ocr");
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat");
        RecordedRequest saveReq = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        assertThat(saveReq.getPath()).isEqualTo("/internal/documents");

        // The OCR text was seeded to memory-service with the {kind, refId} back-pointer to the saved row.
        RecordedRequest seedReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(seedReq.getPath()).isEqualTo("/v1/memories");
        JsonNode seedBody = json.readTree(seedReq.getBody().readUtf8());
        assertThat(seedBody.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(seedBody.path("source").asText()).isEqualTo("docs");
        assertThat(seedBody.path("text").asText()).contains("ДОГОВОР АРЕНДЫ");
        assertThat(seedBody.path("metadata").path("kind").asText()).isEqualTo("document");
        assertThat(seedBody.path("metadata").path("refId").asText()).isEqualTo(docId.toString());

        // ---- Phase 2: search — recovered only via the semantic recall (trigram returns nothing) -----
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"query\":\"договор аренды\"}", "stop", new LlmUsage(30, 12, 42)))));
        mcpDocs.enqueue(jsonResponse("[]"));                         // trigram search: no literal match
        memoryService.enqueue(jsonResponse(json.writeValueAsString(List.of(
                new RecallMemoryHit(recalledMemory(householdId, userId, docId, ocrText), 0.12)))));
        mcpDocs.enqueue(jsonResponse(json.writeValueAsString(archived)));   // refId → getDocument

        NormalizedMessage search = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "найди мой договор аренды", List.of(), "telegram", "de-2", Instant.now());

        IntentResponse searchResp = post(search);
        assertThat(searchResp).isNotNull();
        assertThat(searchResp.text())
                .contains("Договор аренды")
                .contains("https://media.example/v1/media/" + mediaId);

        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/v1/chat");
        // mcp-docs sees the trigram search first, then the getDocument resolving the recall hit's refId.
        RecordedRequest searchReq = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        assertThat(searchReq.getPath()).startsWith("/internal/documents/search");
        RecordedRequest getReq = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        assertThat(getReq.getPath()).isEqualTo("/internal/documents/" + docId);
        RecordedRequest recallReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recallReq.getPath()).isEqualTo("/v1/memories/recall");
        assertThat(recallReq.getBody().readUtf8()).contains("договор аренды");
    }

    private MemoryDto recalledMemory(UUID householdId, UUID userId, UUID docId, String text) {
        ObjectNode meta = json.createObjectNode();
        meta.put("kind", "document");
        meta.put("refId", docId.toString());
        return new MemoryDto(UUID.randomUUID(), householdId, userId, null, "docs", text, meta, Instant.now());
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
