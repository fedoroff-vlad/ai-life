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
    static MockWebServer profileService;

    @BeforeAll
    static void start() throws Exception {
        mcpDocs = new MockWebServer();
        llmGateway = new MockWebServer();
        profileService = new MockWebServer();
        mcpDocs.start();
        llmGateway.start();
        profileService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpDocs.shutdown();
        llmGateway.shutdown();
        profileService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("docs-agent.mcp-docs-url", () -> "http://localhost:" + mcpDocs.getPort());
        r.add("docs-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("docs-agent.public-media-base-url", () -> "https://media.example");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void findCueDistilsQueryAndListsHitsWithLinks() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Two LLM turns now (#475): the DocsIntentRouter classifies the intent (→ doc-finder), then
        // DocFinder distils the query.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"action\":\"skill\",\"name\":\"doc-finder\"}", "stop", new LlmUsage(20, 8, 28)))));
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

        // First the router classify, then the finder distil went through llm-gateway with the SKILL prompt.
        RecordedRequest routerReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(routerReq.getPath()).isEqualTo("/v1/chat");
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

        // Router classify (→ doc-finder), then the finder distil (#475).
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"action\":\"skill\",\"name\":\"doc-finder\"}", "stop", new LlmUsage(20, 8, 28)))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"query\":\"страховка\"}", "stop", new LlmUsage(15, 6, 21)))));
        mcpDocs.enqueue(jsonResponse("[]"));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "найди мою страховку", List.of(), "telegram", "91", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Ничего не нашёл");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);       // router classify
        llmGateway.takeRequest(2, TimeUnit.SECONDS);       // finder distil
        RecordedRequest searchReq = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        // No docType named → the filter is omitted from the query string.
        assertThat(searchReq.getPath()).doesNotContain("docType=");
    }

    @Test
    void familyCueUnionsPersonalAndSharedHouseholds() throws Exception {
        UUID personalHh = UUID.randomUUID();
        UUID sharedHh = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Router classify (→ doc-finder), then the finder distil (#475).
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"action\":\"skill\",\"name\":\"doc-finder\"}", "stop", new LlmUsage(20, 8, 28)))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "{\"query\":\"гарантия\"}", "stop", new LlmUsage(20, 8, 28)))));
        // households union: the member's personal + one shared household (ADR-0002 slice 7b read path).
        profileService.enqueue(jsonResponse(json.writeValueAsString(List.of(personalHh, sharedHh))));
        // one trigram search per household — a personal doc + a family (shared) doc.
        mcpDocs.enqueue(jsonResponse(json.writeValueAsString(List.of(new DocumentDto(
                UUID.randomUUID(), personalHh, userId, "media-p", "warranty", "Гарантия на ноутбук",
                "DNS", LocalDate.of(2026, 3, 1), null, null, "гарантия ноутбук", null, Instant.now())))));
        mcpDocs.enqueue(jsonResponse(json.writeValueAsString(List.of(new DocumentDto(
                UUID.randomUUID(), sharedHh, null, "media-s", "warranty", "Гарантия на холодильник",
                "М.Видео", LocalDate.of(2026, 2, 1), null, null, "гарантия холодильник", null, Instant.now())))));

        NormalizedMessage msg = new NormalizedMessage(userId, personalHh, MessageScope.PRIVATE,
                "найди наши документы на гарантию", List.of(), "telegram", "92", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        // both the personal and the shared-household documents appear (union across the set).
        assertThat(resp.text())
                .contains("Гарантия на ноутбук")
                .contains("Гарантия на холодильник");

        llmGateway.takeRequest(2, TimeUnit.SECONDS);       // router classify
        llmGateway.takeRequest(2, TimeUnit.SECONDS);       // finder distil
        // the household set was resolved for the acting user.
        RecordedRequest householdsReq = profileService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(householdsReq.getPath()).contains("/households").contains(userId.toString());
        // two trigram searches ran — one per household in the set (order is non-deterministic).
        RecordedRequest s1 = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest s2 = mcpDocs.takeRequest(2, TimeUnit.SECONDS);
        assertThat(s1.getPath()).startsWith("/internal/documents/search");
        assertThat(s2.getPath()).startsWith("/internal/documents/search");
        assertThat(s1.getPath() + s2.getPath())
                .contains("householdId=" + personalHh)
                .contains("householdId=" + sharedHh);
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
