package dev.fedorov.ailife.agents.researcher;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.web.PageContent;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cheap-first research flow end-to-end through the agent's HTTP surface
 * ({@code POST /agents/researcher/intent}). mcp-web (`/internal/search` + `/internal/fetch`) and
 * llm-gateway are MockWebServers. {@code fetch-top-n=1} keeps the single fetch deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "researcher-agent.fetch-top-n=1")
@AutoConfigureWebTestClient
class ResearcherFlowTest {

    static MockWebServer mcpWeb;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpWeb = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpWeb.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpWeb.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("researcher-agent.mcp-web-url", () -> "http://localhost:" + mcpWeb.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void searchesThenFetchesThenSynthesizesWithSources() throws Exception {
        // mcp-web: search returns two hits (an article + a video), then fetch returns the article text.
        var hits = List.of(
                new WebSearchHit("Bed Leveling Guide", "https://example.com/guide", "Step by step."),
                new WebSearchHit("Leveling Video", "https://youtube.com/watch?v=abc", "Watch."));
        mcpWeb.enqueue(jsonResponse(new WebSearchResult("3d printer bed leveling", hits)));
        mcpWeb.enqueue(jsonResponse(new PageContent(
                "https://example.com/guide", "Bed Leveling Guide",
                "Heat the bed to sixty degrees, then run the paper test at each corner.", false)));
        // llm-gateway: the single synthesis call.
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large",
                        "Краткая выжимка по калибровке стола. Источники ниже.",
                        "stop", new LlmUsage(50, 30, 80)))));

        var msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "найди как откалибровать стол 3D-принтера", List.of(), "telegram", "1", Instant.now());

        IntentResponse resp = http.post().uri("/agents/researcher/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.agent()).isEqualTo("researcher");
        assertThat(resp.text()).contains("выжимка");

        // Cheap-first order: search first, then fetch the top hit — both before the LLM call.
        RecordedRequest searchReq = mcpWeb.takeRequest(2, TimeUnit.SECONDS);
        assertThat(searchReq.getPath()).isEqualTo("/internal/search");
        assertThat(searchReq.getBody().readUtf8()).contains("3D");
        RecordedRequest fetchReq = mcpWeb.takeRequest(2, TimeUnit.SECONDS);
        assertThat(fetchReq.getPath()).isEqualTo("/internal/fetch");
        assertThat(fetchReq.getBody().readUtf8()).contains("example.com/guide");

        // The single synthesis prompt carries the gathered corpus (both source urls + fetched text).
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body)
                .contains("sources")
                .contains("example.com/guide")
                .contains("youtube.com")
                .contains("sixty degrees"); // the fetched page text reached the model
        // Exactly one LLM call — token economy.
        assertThat(llmGateway.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void videoTopHitIsNotFetchedSnippetCarriesIt() throws Exception {
        // The single top hit is a video — its page is boilerplate, so the flow must NOT fetch it;
        // the search snippet is what describes it (the user wants a link + short description).
        var hits = List.of(new WebSearchHit(
                "Bed Leveling Video", "https://www.youtube.com/watch?v=abc",
                "Level the bed cold in three steps."));
        mcpWeb.enqueue(jsonResponse(new WebSearchResult("bed leveling video", hits)));
        llmGateway.enqueue(new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse(
                        "mock-large", "Видео по калибровке стола — ссылка ниже.",
                        "stop", new LlmUsage(20, 10, 30)))));

        var msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "find a video about bed leveling", List.of(), "telegram", "2", Instant.now());

        http.post().uri("/agents/researcher/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk();

        // mcp-web got the search — but NO /internal/fetch for the video page.
        RecordedRequest searchReq = mcpWeb.takeRequest(2, TimeUnit.SECONDS);
        assertThat(searchReq.getPath()).isEqualTo("/internal/search");
        assertThat(mcpWeb.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();

        // The corpus still carries the video link + its snippet (the description source).
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        String body = llmReq.getBody().readUtf8();
        assertThat(body)
                .contains("youtube.com/watch?v=abc")
                .contains("Level the bed cold in three steps");
    }

    private MockResponse jsonResponse(Object body) throws Exception {
        return new MockResponse()
                .setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(body));
    }
}
