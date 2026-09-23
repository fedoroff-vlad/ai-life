package dev.fedorov.ailife.agents.inventory;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the "где лежит X" flow (IN-e) through the agent's HTTP surface: the router picks
 * {@code item-finder}, one LLM turn distils the thing out of the question, and the search answers
 * with the <b>place</b>. MockWebServers stand in for llm-gateway and mcp-inventory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class ItemFinderTest {

    static MockWebServer mcpInventory;
    static MockWebServer llmGateway;

    @BeforeAll
    static void start() throws Exception {
        mcpInventory = new MockWebServer();
        llmGateway = new MockWebServer();
        mcpInventory.start();
        llmGateway.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpInventory.shutdown();
        llmGateway.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("inventory-agent.mcp-inventory-url", () -> "http://localhost:" + mcpInventory.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void answersWithTheContainerAndItsZone() throws Exception {
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"ёлочные игрушки\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(List.of(
                hit("ёлочные игрушки", "B-07", "Новый год", "кладовка")))));

        IntentResponse response = ask("где лежат ёлочные игрушки?");

        assertThat(response.text())
                .contains("ёлочные игрушки")
                .contains("B-07")
                .contains("Новый год")
                .contains("кладовка");

        // The question's interrogatives must not reach the search — the stored names came from photo
        // captions, so "где лежат" would only dilute the match.
        RecordedRequest search = take(mcpInventory);
        assertThat(search.getPath()).startsWith("/internal/items/search");
        assertThat(search.getPath()).doesNotContain("%D0%B3%D0%B4%D0%B5"); // "где"
    }

    @Test
    void saysSoPlainlyWhenNothingMatches() throws Exception {
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"дрель\"}"));
        mcpInventory.enqueue(jsonResponse("[]"));

        IntentResponse response = ask("куда я убрал дрель");

        assertThat(response.text()).contains("Не нашёл").contains("дрель");
        // Never invent a location for a thing that was never packed.
        assertThat(response.text()).doesNotContain("коробка B-");
    }

    @Test
    void namesTheContainerEvenWhenItHasNoZoneYet() throws Exception {
        llmGateway.enqueue(llm("{\"action\":\"skill\",\"name\":\"item-finder\"}"));
        llmGateway.enqueue(llm("{\"query\":\"дрель\"}"));
        mcpInventory.enqueue(jsonResponse(json.writeValueAsString(List.of(
                hit("дрель", "B-03", "инструменты", null)))));

        IntentResponse response = ask("в какой коробке дрель");

        assertThat(response.text()).contains("B-03").contains("зона не указана");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private IntentResponse ask(String text) {
        return http.post().uri("/agents/inventory/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(),
                        MessageScope.PRIVATE, text, List.of(), "telegram", "1", Instant.now()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(IntentResponse.class)
                .returnResult().getResponseBody();
    }

    private static ItemLocationDto hit(String title, String code, String label, String zoneName) {
        UUID containerId = UUID.randomUUID();
        UUID zoneId = zoneName == null ? null : UUID.randomUUID();
        return new ItemLocationDto(
                new ItemDto(UUID.randomUUID(), containerId, "media-1", title, null, null, 1,
                        Instant.now()),
                new ContainerDto(containerId, UUID.randomUUID(), null, zoneId, code, label, "box",
                        "abcdef0123456789", "packed", null, null, Instant.now(), null),
                zoneName == null ? null : new StorageZoneDto(zoneId, UUID.randomUUID(), null,
                        zoneName, "closet", null, null, Instant.now()));
    }

    private MockResponse llm(String content) {
        return jsonResponse(json.writeValueAsString(
                new LlmChatResponse("mock-large", content, "stop", new LlmUsage(20, 10, 30))));
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    private static RecordedRequest take(MockWebServer server) throws Exception {
        return server.takeRequest(5, TimeUnit.SECONDS);
    }
}
