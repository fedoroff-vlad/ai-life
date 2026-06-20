package dev.fedorov.ailife.agents.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.media.CaptionResult;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the receipt-parser confirm-before-write flow (Stage 4 / A4) through the agent's HTTP
 * surface. The photo intent ({@code POST /agents/finance/intent}) parses a draft and replies with a
 * confirmation + a {@code pendingAction} — it does NOT write. The user's reply
 * ({@code POST /agents/finance/resume}) writes on "да" and cancels otherwise.
 *
 * <p>As of MP-c the vision call lives in the shared {@code mcp-media-processing} capability — the
 * receipt flow no longer fetches bytes or hits llm-gateway directly; it calls the capability's
 * {@code POST /internal/caption} passthrough. MockWebServers stand in for mcp-media-processing /
 * mcp-finance / memory-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReceiptFlowTest {

    static MockWebServer mediaProcessing;
    static MockWebServer mcpFinance;
    static MockWebServer memory;

    @BeforeAll
    static void start() throws Exception {
        mediaProcessing = new MockWebServer();
        mcpFinance = new MockWebServer();
        memory = new MockWebServer();
        mediaProcessing.start();
        mcpFinance.start();
        memory.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mediaProcessing.shutdown();
        mcpFinance.shutdown();
        memory.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.mcp-media-processing-url", () -> "http://localhost:" + mediaProcessing.getPort());
        r.add("finance-agent.mcp-finance-url", () -> "http://localhost:" + mcpFinance.getPort());
        r.add("finance-agent.memory-service-url", () -> "http://localhost:" + memory.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void photoAttachmentParsesDraftAndAsksToConfirmWithoutWriting() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        var draftJson = "{\"amount\": 4.50, \"currency\": \"EUR\", \"merchant\": \"Starbucks\", \"date\": \"2026-06-01\"}";
        // mcp-media-processing caption passthrough returns the extracted draft.
        mediaProcessing.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new CaptionResult(draftJson, "mock-vision"))));
        // mcp-finance: only the account list — NO transaction POST in the confirm step.
        mcpFinance.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(List.of(new FinAccountDto(
                        accountId, householdId, null, "Main card", "card", "EUR",
                        BigDecimal.ZERO, false, Instant.now())))));
        // memory-from-chat: the receipt caption is dropped at /v1/observations (MFC-c).
        memory.enqueue(new MockResponse().setResponseCode(202));

        var msg = new NormalizedMessage(
                UUID.randomUUID(), householdId, MessageScope.PRIVATE, "вот чек за кофе",
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "55", Instant.now());

        IntentResponse resp = http.post().uri("/agents/finance/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Записать").contains("Starbucks").contains("Main card");
        // The confirm carries a pendingAction so the orchestrator locks the conversation to finance.
        assertThat(resp.pendingAction()).isNotNull();
        assertThat(resp.pendingAction().path("flow").asText()).isEqualTo("receipt-confirm");
        assertThat(resp.pendingAction().path("input").path("accountId").asText())
                .isEqualTo(accountId.toString());
        assertThat(new BigDecimal(resp.pendingAction().path("input").path("amount").asText()))
                .isEqualByComparingTo("-4.50");

        // The capability's caption passthrough was called with the media id + the SKILL instruction.
        RecordedRequest captionReq = mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        assertThat(captionReq.getMethod()).isEqualTo("POST");
        assertThat(captionReq.getPath()).isEqualTo("/internal/caption");
        JsonNode captionBody = json.readTree(captionReq.getBody().readUtf8());
        assertThat(captionBody.path("mediaId").asText()).isEqualTo(mediaId);
        assertThat(captionBody.path("instruction").asText())
                .contains("strict JSON")            // the receipt-parser SKILL.md prompt
                .contains("вот чек за кофе");        // the user's caption folded in as a hint

        RecordedRequest accountsReq = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(accountsReq.getMethod()).isEqualTo("GET");
        assertThat(accountsReq.getPath()).startsWith("/internal/accounts");
        // No write happened in the confirm step.
        assertThat(mcpFinance.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();

        // The caption (+ parsed merchant) was dropped at memory-service for fact extraction.
        RecordedRequest observeReq = memory.takeRequest(2, TimeUnit.SECONDS);
        assertThat(observeReq).isNotNull();
        assertThat(observeReq.getMethod()).isEqualTo("POST");
        assertThat(observeReq.getPath()).isEqualTo("/v1/observations");
        JsonNode observeBody = json.readTree(observeReq.getBody().readUtf8());
        assertThat(observeBody.path("text").asText())
                .contains("вот чек за кофе").contains("Starbucks");
        assertThat(observeBody.path("householdId").asText()).isEqualTo(householdId.toString());
    }

    @Test
    void captionlessReceiptEmitsNoObservation() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        var draftJson = "{\"amount\": 4.50, \"currency\": \"EUR\", \"merchant\": \"Starbucks\", \"date\": \"2026-06-01\"}";
        mediaProcessing.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new CaptionResult(draftJson, "mock-vision"))));
        mcpFinance.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(List.of(new FinAccountDto(
                        accountId, householdId, null, "Main card", "card", "EUR",
                        BigDecimal.ZERO, false, Instant.now())))));

        // No caption → a bare transaction with no durable personal fact → emit nothing.
        var msg = new NormalizedMessage(
                UUID.randomUUID(), householdId, MessageScope.PRIVATE, null,
                List.of(new Attachment("image", "image/jpeg", mediaId, null)),
                "telegram", "57", Instant.now());

        http.post().uri("/agents/finance/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk();

        // Drain the flow's requests so they don't leak into the shared static servers.
        mediaProcessing.takeRequest(2, TimeUnit.SECONDS);
        mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        // No caption → nothing dropped at memory-service.
        assertThat(memory.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void resumeAffirmativeWritesTheStashedTransaction() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        mcpFinance.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new FinTransactionDto(
                        UUID.randomUUID(), householdId, accountId, null, null,
                        new BigDecimal("-4.50"), "EUR", Instant.parse("2026-06-01T00:00:00Z"),
                        "Starbucks", "telegram", null, Instant.now()))));

        ResumeRequest req = resumeReq(householdId, accountId, "да");

        IntentResponse resp = http.post().uri("/agents/finance/resume")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Добавил").contains("Starbucks");
        assertThat(resp.pendingAction()).isNull(); // resolved → orchestrator clears the lock

        RecordedRequest addReq = mcpFinance.takeRequest(2, TimeUnit.SECONDS);
        assertThat(addReq.getMethod()).isEqualTo("POST");
        assertThat(addReq.getPath()).isEqualTo("/internal/transaction");
        JsonNode addBody = json.readTree(addReq.getBody().readUtf8());
        assertThat(new BigDecimal(addBody.path("amount").asText())).isEqualByComparingTo("-4.50");
        assertThat(addBody.path("note").asText()).isEqualTo("Starbucks");
    }

    @Test
    void resumeNegativeCancelsWithoutWriting() throws Exception {
        ResumeRequest req = resumeReq(UUID.randomUUID(), UUID.randomUUID(), "нет");

        IntentResponse resp = http.post().uri("/agents/finance/resume")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Отменил");
        assertThat(resp.pendingAction()).isNull();
        // Cancel touches no service.
        assertThat(mcpFinance.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private ResumeRequest resumeReq(UUID householdId, UUID accountId, String text) {
        var input = new AddTransactionInput(householdId, accountId, null, null,
                new BigDecimal("-4.50"), "EUR", Instant.parse("2026-06-01T00:00:00Z"),
                "Starbucks", "telegram", null);
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "receipt-confirm");
        pending.set("input", json.valueToTree(input));
        pending.put("accountName", "Main card");
        var msg = new NormalizedMessage(UUID.randomUUID(), householdId, MessageScope.PRIVATE,
                text, List.of(), "telegram", "56", Instant.now());
        return new ResumeRequest(msg, pending);
    }
}
