package dev.fedorov.ailife.agents.travel.flow;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.travel.AddFundingInput;
import dev.fedorov.ailife.contracts.travel.LogExchangeInput;
import dev.fedorov.ailife.contracts.travel.LogExpenseInput;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.contracts.travel.TripExchangeDto;
import dev.fedorov.ailife.contracts.travel.TripExpenseDto;
import dev.fedorov.ailife.contracts.travel.TripFundingDto;
import dev.fedorov.ailife.contracts.travel.TripLedgerDto;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the trip-wallet flow (EX-b) through the agent's HTTP surface ({@code POST
 * /agents/travel/intent}) with wallet cues. MockWebServers stand in for llm-gateway (the WalletExtractor
 * turn), mcp-travel (the {@code /internal/trips/*} store), and media-service (the wallet board). The
 * deterministic balance math is {@link TripLedger} (unit-tested separately); here we assert the flow wires
 * create/fund/exchange/spend/tally end to end, renders the board, and soft-fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class WalletFlowTest {

    private static final UUID TRIP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    static MockWebServer llmGateway;
    static MockWebServer mcpTravel;
    static MockWebServer mediaService;
    static MockWebServer memoryService;

    @BeforeAll
    static void start() throws Exception {
        llmGateway = new MockWebServer();
        mcpTravel = new MockWebServer();
        mediaService = new MockWebServer();
        memoryService = new MockWebServer();
        llmGateway.start();
        mcpTravel.start();
        mediaService.start();
        memoryService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        llmGateway.shutdown();
        mcpTravel.shutdown();
        mediaService.shutdown();
        memoryService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("travel-agent.mcp-travel-url", () -> "http://localhost:" + mcpTravel.getPort());
        r.add("travel-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("travel-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("travel-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        // Unused by the wallet flow, but the context builds their WebClient beans — point them anywhere.
        r.add("travel-agent.mcp-weather-url", () -> "http://localhost:1");
        r.add("travel-agent.mcp-web-url", () -> "http://localhost:1");
        r.add("travel-agent.mcp-travel-search-url", () -> "http://localhost:1");
        r.add("travel-agent.orchestrator-url", () -> "http://localhost:1");
        r.add("travel-agent.mcp-chart-render-url", () -> "http://localhost:1");
        // The deterministic flows use the HTTP /internal passthroughs; the MCP/SSE client isn't needed.
        r.add("spring.ai.mcp.client.enabled", () -> "false");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    static final CopyOnWriteArrayList<String> travelPaths = new CopyOnWriteArrayList<>();
    static final AtomicReference<String> boardBody = new AtomicReference<>();
    static final AtomicReference<String> noteBody = new AtomicReference<>();

    @BeforeEach
    void reset() {
        travelPaths.clear();
        boardBody.set(null);
        noteBody.set(null);
        mediaService.setDispatcher(mediaOk());
        memoryService.setDispatcher(noteOk());
    }

    @Test
    void createTripPersistsAndConfirms() {
        llmGateway.setDispatcher(llmAction("{\"action\":\"create\",\"title\":\"Тайланд\",\"destination\":\"Пхукет\"}"));
        mcpTravel.setDispatcher(walletStore(true));

        IntentResponse resp = post("создай поездку в Тайланд");
        assertThat(resp.text()).contains("Создал поездку «Тайланд»");
        assertThat(travelPaths).anyMatch(p -> p.equals("/internal/trips"));
    }

    @Test
    void fundRecordsAcquiredCurrencyOnActiveTrip() {
        llmGateway.setDispatcher(llmAction("{\"action\":\"fund\",\"currency\":\"USD\",\"amount\":500,\"rateToHome\":90}"));
        mcpTravel.setDispatcher(walletStore(true));

        IntentResponse resp = post("завёл 500 долларов по 90");
        assertThat(resp.text()).contains("+500 USD").contains("курс 90");
        assertThat(travelPaths).anyMatch(p -> p.equals("/internal/trips/fundings"));
    }

    @Test
    void exchangeRecordsPairedSwap() {
        llmGateway.setDispatcher(llmAction(
                "{\"action\":\"exchange\",\"fromCurrency\":\"RUB\",\"fromAmount\":36000,\"toCurrency\":\"THB\",\"toAmount\":40000}"));
        mcpTravel.setDispatcher(walletStore(true));

        IntentResponse resp = post("поменял 36000 рублей на 40000 бат");
        assertThat(resp.text()).contains("−36000 RUB").contains("+40000 THB");
        assertThat(travelPaths).anyMatch(p -> p.equals("/internal/trips/exchanges"));
    }

    @Test
    void spendRecordsExpense() {
        llmGateway.setDispatcher(llmAction(
                "{\"action\":\"spend\",\"currency\":\"THB\",\"amount\":2000,\"description\":\"ужин\"}"));
        mcpTravel.setDispatcher(walletStore(true));

        IntentResponse resp = post("потратил 2000 бат на ужин");
        assertThat(resp.text()).contains("−2000 THB").contains("ужин");
        assertThat(travelPaths).anyMatch(p -> p.equals("/internal/trips/expenses"));
    }

    @Test
    void tallyComputesRemainingAndRendersBoard() {
        llmGateway.setDispatcher(llmAction("{\"action\":\"tally\"}"));
        mcpTravel.setDispatcher(walletStore(true));

        IntentResponse resp = post("сколько осталось");
        // Deterministic tally: RUB 100000 − 36000 exchange = 64000; THB 40000 − 39800 = 200 @0.9 = 180 ₽.
        assertThat(resp.text()).contains("Кошелёк поездки «Тайланд»")
                .contains("THB — осталось 200")
                .contains("Всего осталось ≈ 64180 RUB")
                .contains("Открыть кошелёк:");
        // The rendered board carries the per-currency rows + the ₽ total + the no-settlement line.
        String board = boardBody.get();
        assertThat(board).as("board not captured").isNotNull();
        assertThat(board).contains("Остаток по валютам").contains("64180").contains("кто кому должен");
    }

    @Test
    void fundWithoutActiveTripTellsOwnerToCreateOne() {
        llmGateway.setDispatcher(llmAction("{\"action\":\"fund\",\"currency\":\"USD\",\"amount\":500}"));
        mcpTravel.setDispatcher(walletStore(false));   // no active trip → getActiveTrip 204

        IntentResponse resp = post("завёл 500 долларов");
        assertThat(resp.text()).contains("Сначала создайте поездку");
        assertThat(travelPaths).noneMatch(p -> p.equals("/internal/trips/fundings"));
    }

    @Test
    void tallyBoardHiccupFallsBackToTextOnly() {
        llmGateway.setDispatcher(llmAction("{\"action\":\"tally\"}"));
        mcpTravel.setDispatcher(walletStore(true));
        mediaService.setDispatcher(serverError());   // board store down → text-only

        IntentResponse resp = post("подведи итог");
        assertThat(resp.text()).contains("Кошелёк поездки «Тайланд»").contains("Всего осталось");
        assertThat(resp.text()).doesNotContain("Открыть кошелёк:");
    }

    @Test
    void closeTalliesClosesAndDepositsFinanceSpendSignal() {
        llmGateway.setDispatcher(llmAction("{\"action\":\"close\"}"));
        mcpTravel.setDispatcher(walletStore(true));

        IntentResponse resp = post("закрой поездку");
        // The trip is tallied, closed in the store, and the reply carries the spend total + the board.
        assertThat(resp.text()).contains("Поездка «Тайланд» закрыта").contains("Потрачено ≈ 35820 RUB")
                .contains("Открыть кошелёк:");
        assertThat(travelPaths).anyMatch(p -> p.equals("/internal/trips/" + TRIP_ID + "/close"));

        // A finance spend-signal note is deposited into the shared second brain (household-shared, tagged).
        String note = noteBody.get();
        assertThat(note).as("finance note not captured").isNotNull();
        assertThat(note).contains("Расходы на поездку").contains("35820")
                .contains("trip-spend").contains("finance");
    }

    @Test
    void closeStillClosesWhenFinanceNoteWriteFails() {
        llmGateway.setDispatcher(llmAction("{\"action\":\"close\"}"));
        mcpTravel.setDispatcher(walletStore(true));
        memoryService.setDispatcher(serverError());   // memory down → note soft-fails

        IntentResponse resp = post("заверши поездку");
        assertThat(resp.text()).contains("Поездка «Тайланд» закрыта").contains("Потрачено ≈ 35820 RUB");
        assertThat(travelPaths).anyMatch(p -> p.equals("/internal/trips/" + TRIP_ID + "/close"));
    }

    // --- dispatchers ---

    /**
     * llm-gateway: two turns now (#475). The {@link dev.fedorov.ailife.agents.travel.intent.TravelIntentRouter}
     * classify turn comes first (detected by the router prompt marker) → route it to {@code trip-wallet}; the
     * following {@code WalletExtractor} turn returns the given action JSON.
     */
    private Dispatcher llmAction(String actionJson) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    String body = request.getBody().readUtf8();
                    String content = body.contains("routing a message for the travel agent")
                            ? "{\"action\":\"skill\",\"name\":\"trip-wallet\"}"
                            : actionJson;
                    return jsonResponse(json.writeValueAsString(new LlmChatResponse(
                            "mock", content, "stop", new LlmUsage(20, 10, 30))));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /**
     * mcp-travel /internal/trips/* store. {@code hasActiveTrip} toggles whether {@code GET /active} returns
     * a trip or 204. Writes echo the posted DTO; the ledger read returns a fixed multi-currency wallet
     * (RUB funding + a RUB→THB exchange + a THB expense) so the tally is deterministic.
     */
    private Dispatcher walletStore(boolean hasActiveTrip) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                String method = request.getMethod();
                String body = request.getBody().readUtf8();
                if (path != null) travelPaths.add(path.split("\\?")[0]);
                try {
                    if ("GET".equals(method) && path != null && path.startsWith("/internal/trips/active")) {
                        return hasActiveTrip ? jsonResponse(json.writeValueAsString(trip()))
                                : new MockResponse().setResponseCode(204);
                    }
                    if (path != null && path.contains("/ledger")) {
                        return jsonResponse(json.writeValueAsString(ledger()));
                    }
                    if ("POST".equals(method) && path != null && path.contains("/close")) {
                        return jsonResponse(json.writeValueAsString(new TripDto(TRIP_ID, UUID.randomUUID(),
                                null, "Тайланд", "Пхукет", null, null, "RUB", "closed",
                                Instant.now(), Instant.now())));
                    }
                    if ("POST".equals(method) && path != null && path.equals("/internal/trips")) {
                        return jsonResponse(json.writeValueAsString(trip()));
                    }
                    if ("POST".equals(method) && path != null && path.equals("/internal/trips/fundings")) {
                        AddFundingInput in = json.readValue(body, AddFundingInput.class);
                        return jsonResponse(json.writeValueAsString(new TripFundingDto(
                                UUID.randomUUID(), TRIP_ID, in.currency(), in.amount(), in.rateToHome(),
                                Instant.now(), in.note())));
                    }
                    if ("POST".equals(method) && path != null && path.equals("/internal/trips/exchanges")) {
                        LogExchangeInput in = json.readValue(body, LogExchangeInput.class);
                        return jsonResponse(json.writeValueAsString(new TripExchangeDto(
                                UUID.randomUUID(), TRIP_ID, in.fromCurrency(), in.fromAmount(),
                                in.toCurrency(), in.toAmount(), Instant.now(), in.note())));
                    }
                    if ("POST".equals(method) && path != null && path.equals("/internal/trips/expenses")) {
                        LogExpenseInput in = json.readValue(body, LogExpenseInput.class);
                        return jsonResponse(json.writeValueAsString(new TripExpenseDto(
                                UUID.randomUUID(), TRIP_ID, in.currency(), in.amount(), in.category(),
                                in.description(), Instant.now(), Instant.now())));
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static TripDto trip() {
        return new TripDto(TRIP_ID, UUID.randomUUID(), null, "Тайланд", "Пхукет", null, null,
                "RUB", "active", Instant.now(), Instant.now());
    }

    private static TripLedgerDto ledger() {
        TripFundingDto rub = new TripFundingDto(UUID.randomUUID(), TRIP_ID, "RUB",
                new BigDecimal("100000"), BigDecimal.ONE, Instant.now(), null);
        TripExchangeDto swap = new TripExchangeDto(UUID.randomUUID(), TRIP_ID, "RUB",
                new BigDecimal("36000"), "THB", new BigDecimal("40000"), Instant.now(), null);
        TripExpenseDto spend = new TripExpenseDto(UUID.randomUUID(), TRIP_ID, "THB",
                new BigDecimal("39800"), null, "отель+еда", Instant.now(), Instant.now());
        return new TripLedgerDto(trip(), List.of(), List.of(rub), List.of(swap), List.of(spend));
    }

    /** memory-service /v1/notes: capture the deposited finance spend-signal note, echo a NoteDto. */
    private Dispatcher noteOk() {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                noteBody.set(request.getBody().readUtf8());
                try {
                    return jsonResponse(json.writeValueAsString(new NoteDto(
                            UUID.randomUUID(), UUID.randomUUID(), null, "note", "fact",
                            List.of("finance", "travel"), "travel-agent", null, "body", null,
                            Instant.now(), Instant.now())));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private Dispatcher mediaOk() {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                boardBody.set(request.getBody().readUtf8());
                try {
                    return jsonResponse(json.writeValueAsString(new MediaObjectDto(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "file",
                            "text/html", 2048, "sha", "travel", Instant.now())));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static Dispatcher serverError() {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(500);
            }
        };
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    private IntentResponse post(String text) {
        NormalizedMessage msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(),
                MessageScope.PRIVATE, text, List.of(), "telegram", "1", Instant.now());
        return http.post().uri("/agents/travel/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }
}
