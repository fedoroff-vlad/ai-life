package dev.fedorov.ailife.agents.stylist;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import okhttp3.mockwebserver.Dispatcher;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the wardrobe gap analysis (ST-j): a text request → gather wardrobe + profile → one LLM
 * synthesis returning a gap JSON → render the gap board (what-to-buy with priority/price tier, "do
 * not buy", coverage before/after, palette) → store → reply with a link. MockWebServers stand in for
 * mcp-wardrobe, llm-gateway and media-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GapAnalystTest {

    static MockWebServer mcpWardrobe;
    static MockWebServer llm;
    static MockWebServer mediaService;
    static volatile String itemsJson = "[]";

    @BeforeAll
    static void start() throws Exception {
        mcpWardrobe = new MockWebServer();
        llm = new MockWebServer();
        mediaService = new MockWebServer();
        mcpWardrobe.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest req) {
                String path = req.getPath() == null ? "" : req.getPath();
                if (path.startsWith("/internal/items")) {
                    return new MockResponse().setHeader("content-type", "application/json").setBody(itemsJson);
                }
                return new MockResponse().setResponseCode(404); // no profile
            }
        });
        mcpWardrobe.start();
        llm.start();
        mediaService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpWardrobe.shutdown();
        llm.shutdown();
        mediaService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("stylist-agent.mcp-wardrobe-url", () -> "http://localhost:" + mcpWardrobe.getPort());
        r.add("stylist-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("stylist-agent.public-media-base-url", () -> "https://files.example.test");
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llm.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void gapAnalysisRendersWhatToBuyAndCoverage() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        itemsJson = json.writeValueAsString(List.of(
                new WardrobeItemDto(UUID.randomUUID(), householdId, null, "navy coat", "outerwear",
                        "navy", "wool", "plain", "winter", "smart", null, Instant.now())));

        String gap = "{\"gaps\":[{\"name\":\"Tailored blazer\",\"fills\":\"structure & authority\","
                + "\"priority\":\"essential\",\"priceTier\":\"investment\"}],"
                + "\"doNotBuy\":[{\"name\":\"micro mini skirt\",\"reason\":\"breaks your vertical line\"}],"
                + "\"coverageBefore\":\"52%\",\"coverageAfter\":\"88%\","
                + "\"focusAreas\":[\"public speaking\",\"travel\"],"
                + "\"palette\":[{\"hex\":\"#042C53\",\"name\":\"deep blue\"}]}";
        llm.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse("mock-llm", gap, "stop", null))));
        mediaService.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new MediaObjectDto(
                        docId, householdId, userId, "file", "text/html", 999L, null, "stylist", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "что мне докупить?", List.of(), "telegram", "94", Instant.now());

        IntentResponse resp = http.post().uri("/agents/stylist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Что докупить: https://files.example.test/v1/media/" + docId);
        assertThat(resp.text()).contains("52% → 88%");

        RecordedRequest uploadReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(uploadReq.getPath()).isEqualTo("/v1/media");
        String html = uploadReq.getBody().readUtf8();
        assertThat(html).contains("Wardrobe Gap Analysis")
                .contains("Покрытие: 52% → 88%")
                .contains("Что докупить").contains("Tailored blazer").contains("ESSENTIAL")
                .contains("Не покупать").contains("micro mini skirt")
                .contains("Фокус")
                .contains("#042C53");
    }
}
