package dev.fedorov.ailife.agents.docs;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the doc-finder search flow (D-d) through the agent's HTTP surface ({@code POST
 * /agents/docs/intent}): a "find my X" cue → llm-gateway distils a query + optional docType via the
 * {@code doc-finder} SKILL → mcp-docs runs the trigram search → the reply lists the hits with open
 * links. MockWebServers stand in for llm-gateway and mcp-docs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class DocFinderTest {

    static MockWebServer mcpDocs;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpDocs = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpDocs.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpDocs.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("docs-agent.mcp-docs-url", () -> "http://localhost:" + mcpDocs.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("docs-agent.public-media-base-url", () -> "https://media.example");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void findCueDistilsQueryAndListsHitsWithLinks() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"query\":\"договор аренды\",\"docType\":\"contract\"}", "stop",
                new LlmUsage(30, 12, 42)))));
        mcpDocs.enqueue(jsonResponse(json.writeValueAsString(List.of(new DocumentDto(
                UUID.randomUUID(), householdId, userId, "media-9", "contract", "Договор аренды",
                "ООО Ромашка", LocalDate.of(2026, 1, 15), null, null, "договор аренды квартиры",
                null, Instant.now())))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "найди мой договор аренды за прошлый год", List.of(), "telegram", "90", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Договор аренды").contains("https://media.example/v1/media/media-9");

        // The distil went through llm-gateway with the SKILL system prompt + the user text.
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        assertThat(llmReq.getBody().readUtf8()).contains("strict JSON").contains("договор аренды");

        // The search ran with the distilled query + docType filter, household-scoped.
        RecordedRequest searchReq = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        assertThat(searchReq.getPath())
                .startsWith("/internal/documents/search")
                .contains("householdId=" + householdId)
                .contains("docType=contract");
        assertThat(searchReq.getPath()).contains("query=");
    }

    @Test
    void noHitsRepliesNothingFound() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"query\":\"страховка\"}", "stop", new LlmUsage(15, 6, 21)))));
        mcpDocs.enqueue(jsonResponse("[]"));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "найди мою страховку", List.of(), "telegram", "91", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Ничего не нашёл");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest searchReq = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        // No docType named → the filter is omitted from the query string.
        assertThat(searchReq.getPath()).doesNotContain("docType=");
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
