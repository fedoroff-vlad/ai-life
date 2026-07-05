package dev.fedorov.ailife.agents.coordinator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.agents.coordinator.flow.SpecialistBriefs;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end closer for the coordinator's inbound path (#290, Slice A) — the new cross-service wire this
 * agent adds. Proves the full memory-driven synthesis chain over <b>real HTTP boundaries</b> in ONE real
 * coordinator-agent Spring context, with MockWebServers standing in for each hop (memory-service,
 * llm-gateway), asserting the {@code libs/contracts} DTOs survive serialisation each way:
 *
 * <ol>
 *   <li>a cross-cutting {@link NormalizedMessage} arrives at {@code POST /agents/coordinator/intent};</li>
 *   <li>the coordinator recalls from the second brain — the outgoing {@code RecallMemoryRequest} carries
 *       the household + query + k, and memory-service returns {@code RecallMemoryHit}s;</li>
 *   <li>the recalled context reaches the single synthesis turn ({@code POST /v1/chat});</li>
 *   <li>the synthesized {@code LlmChatResponse} becomes the {@link IntentResponse} handed back.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class E2ECoordinateFlowTest {

    static MockWebServer llmGateway;
    static MockWebServer memoryService;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        memoryService = new MockWebServer();
        llmGateway.start();
        memoryService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        memoryService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("coordinator-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        // Pin the loop to one-shot: this closer asserts the inbound synthesis chain, not the E-later
        // re-gather loop (which is covered in MultiDomainCoordinatorTest). One-shot keeps the ordered
        // enqueue (planning turn, then synthesis turn) deterministic — no self-check turn.
        r.add("coordinator-agent.max-rounds", () -> "1");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void crossCuttingMessageRecallsThenSynthesizesOneGroundedReply() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        memoryService.enqueue(jsonResponse(json.writeValueAsString(List.of(
                new RecallMemoryHit(memoryOf(householdId, userId,
                        "Owner is prepping a talk for a conference in September"), 0.10),
                new RecallMemoryHit(memoryOf(householdId, userId,
                        "Owner set aside Fridays for deep work"), 0.22)))));
        // The FAST planning turn comes first (roster non-empty); it picks no specialist here, so the
        // synthesis stays memory-only. Then the DEFAULT synthesis turn.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-fast", "[]", "stop", new LlmUsage(40, 2, 42)))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Свёл вместе: готовь доклад в пятничные слоты для глубокой работы.",
                "stop", new LlmUsage(180, 70, 250)))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "помоги спланировать неделю с учётом доклада и моих привычек",
                List.of(), "telegram", "e2e-1", Instant.now());

        IntentResponse resp = http.post().uri("/agents/coordinator/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.agent()).isEqualTo("coordinator");
        assertThat(resp.llmModel()).isEqualTo("mock-large");
        assertThat(resp.text()).isEqualTo("Свёл вместе: готовь доклад в пятничные слоты для глубокой работы.");

        // Hop 1: the recall request survived the wire as a RecallMemoryRequest (household + query + k).
        RecordedRequest recallReq = memoryService.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recallReq.getPath()).isEqualTo("/v1/memories/recall");
        JsonNode recallBody = json.readTree(recallReq.getBody().readUtf8());
        assertThat(recallBody.path("householdId").asText()).isEqualTo(householdId.toString());
        assertThat(recallBody.path("query").asText()).contains("спланировать неделю");
        assertThat(recallBody.path("k").asInt()).isEqualTo(5);

        // Hop 2: the synthesis turn (skipping the FAST planning turn) carried the recalled context.
        String llmBody = null;
        for (int i = 0; i < 3; i++) {
            RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
            assertThat(llmReq).isNotNull();
            assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
            String body = llmReq.getBody().readUtf8();
            if (!body.contains(SpecialistBriefs.PLANNER_MARKER)) {
                llmBody = body;
                break;
            }
        }
        assertThat(llmBody).contains("conference in September").contains("Fridays for deep work");
    }

    private static MemoryDto memoryOf(UUID householdId, UUID userId, String text) {
        return new MemoryDto(UUID.randomUUID(), householdId, userId, null, "note", text, null, Instant.now());
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
