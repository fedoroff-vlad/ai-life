package dev.fedorov.ailife.agents.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full receipt-parser path through {@code POST /agents/finance/intent}: a message
 * with an image attachment → fetch bytes from media-service → vision draft from llm-gateway →
 * resolve account + write transaction via mcp-finance. Three MockWebServers stand in for those
 * services; FIFO ordering on the shared mcp-finance mock matches the call order
 * (GET /internal/accounts then POST /internal/transaction).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiptFlowTest {

    static MockWebServer llmGateway;
    static MockWebServer media;
    static MockWebServer mcpFinance;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        media = new MockWebServer();
        mcpFinance = new MockWebServer();
        llmGateway.start();
        media.start();
        mcpFinance.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        media.shutdown();
        mcpFinance.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("finance-agent.media-service-url", () -> "http://localhost:" + media.getPort());
        r.add("finance-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void photoAttachmentParsesDraftAndWritesTransaction() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        // 1. media-service returns the photo bytes.
        media.enqueue(new MockResponse()
                .setHeader("content-type", "image/jpeg")
                .setBody("fake-jpeg-bytes"));
        // 2. llm-gateway (vision) returns a strict-JSON draft as the chat content.
        var draftJson = "{\"amount\": 4.50, \"currency\": \"EUR\", \"merchant\": \"Starbucks\", \"date\": \"2026-06-01\"}";
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-vision", draftJson, "stop", new LlmUsage(30, 10, 40)))));
        // 3. mcp-finance: list accounts, then accept the POST.
        mcpFinance.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(List.of(new FinAccountDto(
                        accountId, householdId, null, "Main card", "card", "EUR",
                        BigDecimal.ZERO, false, Instant.now())))));
        mcpFinance.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new FinTransactionDto(
                        UUID.randomUUID(), householdId, accountId, null, null,
                        new BigDecimal("-4.50"), "EUR", Instant.parse("2026-06-01T00:00:00Z"),
                        "Starbucks", "telegram", null, Instant.now()))));

        var msg = new NormalizedMessage(
                userId, householdId, MessageScope.PRIVATE,
                "вот чек за кофе",
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "55", Instant.now());

        http.post().uri("/agents/finance/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .value(r -> {
                    assertThat(r.agent()).isEqualTo("finance");
                    assertThat(r.text()).contains("Добавил").contains("Starbucks").contains("Main card");
                });

        // media fetched by the attachment's storageUri (the media object id).
        RecordedRequest mediaReq = media.takeRequest();
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media/" + mediaId);

        // llm-gateway was hit on the vision channel with an image part.
        RecordedRequest llmReq = llmGateway.takeRequest();
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        JsonNode llmBody = json.readTree(llmReq.getBody().readUtf8());
        assertThat(llmBody.path("channel").asText()).isEqualTo("vision");
        // 3rd message (after the two system prompts) is the user turn carrying the image.
        JsonNode userMsg = llmBody.path("messages").get(2);
        assertThat(userMsg.path("images").get(0).path("mediaType").asText()).isEqualTo("image/jpeg");
        assertThat(userMsg.path("images").get(0).path("dataBase64").asText()).isNotBlank();

        // mcp-finance: GET accounts then POST the transaction with a negative amount.
        RecordedRequest accountsReq = mcpFinance.takeRequest();
        assertThat(accountsReq.getMethod()).isEqualTo("GET");
        assertThat(accountsReq.getPath()).startsWith("/internal/accounts");

        RecordedRequest addReq = mcpFinance.takeRequest();
        assertThat(addReq.getMethod()).isEqualTo("POST");
        assertThat(addReq.getPath()).isEqualTo("/internal/transaction");
        JsonNode addBody = json.readTree(addReq.getBody().readUtf8());
        assertThat(addBody.path("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(new BigDecimal(addBody.path("amount").asText())).isEqualByComparingTo("-4.50");
        assertThat(addBody.path("source").asText()).isEqualTo("telegram");
        assertThat(addBody.path("note").asText()).isEqualTo("Starbucks");
    }
}
