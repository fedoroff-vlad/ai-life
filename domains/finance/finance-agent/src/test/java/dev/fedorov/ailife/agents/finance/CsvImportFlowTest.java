package dev.fedorov.ailife.agents.finance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvResult;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the CSV-document path through {@code POST /agents/finance/intent}: a message with a
 * {@code file} attachment → fetch bytes from media-service → call mcp-money-pro-import's
 * {@code /internal/import} with auto-create → report the counts. Two MockWebServers stand in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class CsvImportFlowTest {

    static MockWebServer media;
    static MockWebServer moneyProImport;

    @BeforeAll
    static void start() throws Exception {
        media = new MockWebServer();
        moneyProImport = new MockWebServer();
        media.start();
        moneyProImport.start();
    }

    @AfterAll
    static void stop() throws Exception {
        media.shutdown();
        moneyProImport.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("finance-agent.media-service-url", () -> "http://localhost:" + media.getPort());
        r.add("finance-agent.mcp-money-pro-import-url", () -> "http://localhost:" + moneyProImport.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void fileAttachmentRunsAutoCreateImportAndReportsCounts() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String mediaId = UUID.randomUUID().toString();

        media.enqueue(new MockResponse()
                .setHeader("content-type", "text/csv")
                .setBody("Date,Account,Amount,Currency,ID\n2026-01-01,Wallet,-5,EUR,a\n"));
        moneyProImport.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new ImportMoneyProCsvResult(
                        false, 3, 3, 0, 0, List.of(), List.of("Wallet", "Card")))));

        var msg = new NormalizedMessage(
                userId, householdId, MessageScope.PRIVATE,
                "импортируй",
                List.of(new Attachment("file", "text/csv", mediaId, null)),
                "telegram", "70", Instant.now());

        http.post().uri("/agents/finance/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(msg)
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .value(r -> {
                    assertThat(r.agent()).isEqualTo("finance");
                    assertThat(r.text())
                            .contains("Импортировал 3 транзакций")
                            .contains("Создал счета: Wallet, Card");
                });

        // bytes fetched from media-service by the attachment's storageUri
        RecordedRequest mediaReq = media.takeRequest();
        assertThat(mediaReq.getPath()).isEqualTo("/v1/media/" + mediaId);

        // import called with the household, base64 CSV, and auto-create on
        RecordedRequest importReq = moneyProImport.takeRequest();
        assertThat(importReq.getMethod()).isEqualTo("POST");
        assertThat(importReq.getPath()).isEqualTo("/internal/import");
        JsonNode body = json.readTree(importReq.getBody().readUtf8());
        assertThat(body.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(body.path("autoCreateAccounts").asBoolean()).isTrue();
        assertThat(body.path("csvBase64").asText()).isNotBlank();
    }
}
