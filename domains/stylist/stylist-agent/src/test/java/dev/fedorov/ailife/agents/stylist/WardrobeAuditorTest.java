package dev.fedorov.ailife.agents.stylist;

import tools.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the wardrobe audit (ST-h): a text request → gather wardrobe + profile → one LLM
 * synthesis returning a verdict JSON → render the audit board (verdict grid + hero + palette +
 * systemic pattern, garment photos matched by name) → store → reply with a link. MockWebServers
 * stand in for mcp-wardrobe (items + profile), llm-gateway (synthesis) and media-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class WardrobeAuditorTest {

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
                if (path.startsWith("/internal/profile")) {
                    return new MockResponse().setResponseCode(404);
                }
                return new MockResponse().setResponseCode(404);
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
    void auditVerdictsRenderTheBoardWithPhotosAndDiagnosis() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID coatImg = UUID.randomUUID();
        UUID docId = UUID.randomUUID();

        itemsJson = json.writeValueAsString(List.of(
                new WardrobeItemDto(UUID.randomUUID(), householdId, null, "navy coat", "outerwear",
                        "navy", "wool", "plain", "winter", "smart", coatImg, Instant.now()),
                new WardrobeItemDto(UUID.randomUUID(), householdId, null, "logo tee", "top",
                        "white", "cotton", "print", "all-season", "casual", null, Instant.now())));

        String audit = "{\"verdicts\":[{\"name\":\"navy coat\",\"verdict\":\"keep\",\"reason\":\"структурная вещь-герой\"},"
                + "{\"name\":\"logo tee\",\"verdict\":\"remove\",\"reason\":\"спорит с вашим типом\"}],"
                + "\"hero\":[\"navy coat\"],\"systemicPattern\":\"Часто берёте трендовое не своё.\","
                + "\"palette\":[{\"hex\":\"#042C53\",\"name\":\"deep blue\"}]}";
        llm.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new LlmChatResponse("mock-llm", audit, "stop", null))));
        mediaService.enqueue(new MockResponse().setHeader("content-type", "application/json")
                .setBody(json.writeValueAsString(new MediaObjectDto(
                        docId, householdId, userId, "file", "text/html", 999L, null, "stylist", Instant.now()))));

        var msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "сделай ревизию гардероба", List.of(), "telegram", "92", Instant.now());

        IntentResponse resp = http.post().uri("/agents/stylist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Ревизия").contains("https://files.example.test/v1/media/" + docId);
        // оставить 1, под вопросом 0, убрать 1
        assertThat(resp.text()).contains("оставить 1").contains("убрать 1");

        RecordedRequest uploadReq = mediaService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(uploadReq.getPath()).isEqualTo("/v1/media");
        String html = uploadReq.getBody().readUtf8();
        assertThat(html).contains("Wardrobe Audit Board")
                .contains("verdict keep").contains("verdict remove")
                .contains("/v1/media/" + coatImg)        // navy coat photo matched by name
                .contains("Hero pieces")
                .contains("Системная ошибка")
                .contains("#042C53");
    }

    @Test
    void emptyWardrobeInvitesToCatalogue() {
        itemsJson = "[]";
        var msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                "ревизия гардероба", List.of(), "telegram", "93", Instant.now());

        IntentResponse resp = http.post().uri("/agents/stylist/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("гардероб");
        assertThat(resp.text()).doesNotContain("/v1/media/");
    }
}
