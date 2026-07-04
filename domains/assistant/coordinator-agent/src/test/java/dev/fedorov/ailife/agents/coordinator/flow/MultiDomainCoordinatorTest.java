package dev.fedorov.ailife.agents.coordinator.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * Slice test for the coordinator's two entries through its HTTP surface (#290, Slice A). Both funnel
 * through {@link MultiDomainCoordinator#run}: recall from the second brain → one synthesis on the shared
 * Coordinator. MockWebServers stand in for memory-service, llm-gateway, profile-service, and notifier.
 *
 * <ul>
 *   <li><b>Reactive</b> ({@code /intent}): the synthesis request carries the recalled context (grounding),
 *       and the reply is the synthesized text.</li>
 *   <li><b>Proactive</b> ({@code /triggers/coordinator.surface}): a substantial synthesis is delivered to
 *       the owner; a thin one is dropped by the relevance gate (precision over volume).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiDomainCoordinatorTest {

    static MockWebServer memory;
    static MockWebServer llmGateway;
    static MockWebServer profileService;
    static MockWebServer notifier;

    @BeforeAll
    static void start() throws Exception {
        memory = new MockWebServer();
        llmGateway = new MockWebServer();
        profileService = new MockWebServer();
        notifier = new MockWebServer();
        for (MockWebServer s : List.of(memory, llmGateway, profileService, notifier)) {
            s.start();
        }
    }

    @AfterAll
    static void stop() throws Exception {
        for (MockWebServer s : List.of(memory, llmGateway, profileService, notifier)) {
            s.shutdown();
        }
    }

    /** Shared static servers → drain recorded requests between tests so negative assertions stay honest. */
    @AfterEach
    void drain() throws Exception {
        for (MockWebServer s : List.of(memory, llmGateway, profileService, notifier)) {
            while (s.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
                // discard
            }
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("coordinator-agent.memory-service-url", () -> "http://localhost:" + memory.getPort());
        r.add("coordinator-agent.profile-service-url", () -> "http://localhost:" + profileService.getPort());
        r.add("coordinator-agent.notifier-url", () -> "http://localhost:" + notifier.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    @Test
    void reactiveIntentSynthesizesGroundedInRecalledContext() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        memory.setDispatcher(fixedJson(json.writeValueAsString(List.of(
                new RecallMemoryHit(memoryOf(householdId, userId,
                        "Owner is planning a trip to Georgia in August"), 0.12),
                new RecallMemoryHit(memoryOf(householdId, userId,
                        "Owner's travel budget note: keep it under 100k RUB"), 0.20)))));
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Собрал по всем фронтам: план поездки в рамках бюджета.", "stop",
                new LlmUsage(200, 80, 280)))));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "помоги свести всё про мою поездку и бюджет", List.of(), "telegram", "1", Instant.now());

        IntentResponse resp = http.post().uri("/agents/coordinator/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.agent()).isEqualTo("coordinator");
        assertThat(resp.text()).isEqualTo("Собрал по всем фронтам: план поездки в рамках бюджета.");

        // The one synthesis turn carried the recalled second-brain context (grounding).
        RecordedRequest llmReq = llmGateway.takeRequest(2, TimeUnit.SECONDS);
        assertThat(llmReq.getPath()).isEqualTo("/v1/chat");
        String body = llmReq.getBody().readUtf8();
        assertThat(body).contains("Georgia").contains("under 100k RUB");
    }

    @Test
    void proactiveSurfaceDeliversASubstantialSynthesisToTheOwner() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        memory.setDispatcher(fixedJson(json.writeValueAsString(List.of(
                new RecallMemoryHit(memoryOf(householdId, ownerId,
                        "Owner wanted to reconnect with an old colleague about a side project"), 0.15)))));
        notifier.setDispatcher(ok());
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "Идея: свяжись со старым коллегой по сайд-проекту — ты откладывал это.",
                "stop", new LlmUsage(120, 60, 180)))));

        post("coordinator.surface", wake(householdId, ownerId, null));

        RecordedRequest notifyReq = notifier.takeRequest(3, TimeUnit.SECONDS);
        assertThat(notifyReq).isNotNull();
        assertThat(notifyReq.getPath()).isEqualTo("/v1/notify");
        String body = notifyReq.getBody().readUtf8();
        assertThat(body).contains(ownerId.toString()).contains("свяжись со старым коллегой");
        // A known owner is notified directly — no household fan-out lookup.
        assertThat(profileService.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void proactiveSurfaceStaysSilentWhenNothingWorthSurfacing() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        memory.setDispatcher(fixedJson("[]"));
        notifier.setDispatcher(ok());
        // A thin synthesis (< relevance bar) must not become a notification.
        llmGateway.enqueue(jsonResponse(json.writeValueAsString(new LlmChatResponse(
                "mock-large", "нет", "stop", new LlmUsage(40, 2, 42)))));

        post("coordinator.surface", wake(householdId, ownerId, null));

        // The synthesis ran (wake accepted), but nothing was delivered.
        assertThat(llmGateway.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        assertThat(notifier.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    private void post(String kind, AgentWakeRequest req) {
        http.post().uri("/agents/coordinator/triggers/" + kind)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isAccepted();
    }

    private AgentWakeRequest wake(UUID householdId, UUID ownerId, String focus) {
        ObjectNode payload = json.createObjectNode();
        if (ownerId != null) {
            payload.put("ownerId", ownerId.toString());
        }
        if (focus != null) {
            payload.put("focus", focus);
        }
        return new AgentWakeRequest(UUID.randomUUID(), householdId, "coordinator", "coordinator.surface", payload);
    }

    private static MemoryDto memoryOf(UUID householdId, UUID userId, String text) {
        return new MemoryDto(UUID.randomUUID(), householdId, userId, null, "note", text, null, Instant.now());
    }

    private static Dispatcher fixedJson(String body) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return jsonResponse(body);
            }
        };
    }

    private static Dispatcher ok() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(200);
            }
        };
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }
}
