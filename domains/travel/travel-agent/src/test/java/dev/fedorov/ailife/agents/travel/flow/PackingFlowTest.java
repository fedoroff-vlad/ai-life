package dev.fedorov.ailife.agents.travel.flow;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.contracts.weather.ClimateNormals;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.MonthlyNormal;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the packing-list flow (PK-a, #438) through the agent's HTTP surface ({@code POST
 * /agents/travel/intent}) with packing cues. MockWebServers stand in for mcp-travel (active trip +
 * profile), mcp-weather (geocode + climate), and media-service (the packing board). The deterministic
 * list is {@link PackingListComposer} (unit-tested separately); here we assert the flow wires the active
 * trip's season into the list, renders the board, and soft-fails (no trip / unknown weather / board hiccup).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class PackingFlowTest {

    static MockWebServer mcpTravel;
    static MockWebServer mcpWeather;
    static MockWebServer mediaService;
    static MockWebServer memoryService;

    @BeforeAll
    static void start() throws Exception {
        mcpTravel = new MockWebServer();
        mcpWeather = new MockWebServer();
        mediaService = new MockWebServer();
        memoryService = new MockWebServer();
        mcpTravel.start();
        mcpWeather.start();
        mediaService.start();
        memoryService.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpTravel.shutdown();
        mcpWeather.shutdown();
        mediaService.shutdown();
        memoryService.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("travel-agent.mcp-travel-url", () -> "http://localhost:" + mcpTravel.getPort());
        r.add("travel-agent.mcp-weather-url", () -> "http://localhost:" + mcpWeather.getPort());
        r.add("travel-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("travel-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        // Unused by the packing flow, but the context builds their WebClient beans — point them anywhere.
        r.add("travel-agent.mcp-web-url", () -> "http://localhost:1");
        r.add("travel-agent.mcp-travel-search-url", () -> "http://localhost:1");
        r.add("travel-agent.orchestrator-url", () -> "http://localhost:1");
        r.add("travel-agent.mcp-chart-render-url", () -> "http://localhost:1");
        r.add("travel-agent.memory-service-url", () -> "http://localhost:" + memoryService.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:1");
        r.add("spring.ai.mcp.client.enabled", () -> "false");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    static final AtomicReference<String> boardBody = new AtomicReference<>();
    static final AtomicReference<String> noteMethod = new AtomicReference<>();
    static final AtomicReference<String> notePath = new AtomicReference<>();
    static final AtomicReference<String> noteBody = new AtomicReference<>();

    @BeforeEach
    void reset() {
        boardBody.set(null);
        noteMethod.set(null);
        notePath.set(null);
        noteBody.set(null);
        mediaService.setDispatcher(mediaOk());
        memoryService.setDispatcher(memory(null));   // GET list empty → the packing note is created
    }

    /** Active trip → destination's season (hot January in Пхукет) drives the list; board link returned. */
    @Test
    void packsForActiveTripWithSeasonAndRendersBoard() {
        mcpTravel.setDispatcher(travelStore(tripTo("Пхукет", LocalDate.of(2026, 1, 15))));
        mcpWeather.setDispatcher(weather(28.0, 20.0));   // 28 °C → HOT band

        IntentResponse resp = post("что взять с собой");
        assertThat(resp.text()).contains("Список вещей для поездки «Пхукет»").contains("Открыть список:");
        String board = boardBody.get();
        assertThat(board).as("board not captured").isNotNull();
        assertThat(board).contains("Документы и деньги").contains("Головной убор от солнца");
    }

    /** No active trip → a profile-only generic list + a nudge to create one; still a board. */
    @Test
    void noActiveTripGivesGenericListAndNudge() {
        mcpTravel.setDispatcher(travelStore(null));      // getActiveTrip 204

        IntentResponse resp = post("собери список вещей");
        assertThat(resp.text()).contains("Примерный список вещей в дорогу")
                .contains("Паспорт / загранпаспорт")
                .contains("Создайте поездку");
    }

    /** Trip present but the destination can't be geocoded → weather-neutral list, flagged. */
    @Test
    void weatherUnavailableIsFlagged() {
        mcpTravel.setDispatcher(travelStore(tripTo("Атлантида", LocalDate.of(2026, 6, 1))));
        mcpWeather.setDispatcher(noGeo());               // geocode 204 → no coords → UNKNOWN

        IntentResponse resp = post("что упаковать");
        assertThat(resp.text()).contains("Список вещей для поездки «Атлантида»")
                .contains("Погоду для направления уточнить не удалось");
    }

    /** A media/render hiccup ships the text-only list (no board link). */
    @Test
    void boardHiccupFallsBackToTextOnly() {
        mcpTravel.setDispatcher(travelStore(tripTo("Пхукет", LocalDate.of(2026, 1, 15))));
        mcpWeather.setDispatcher(weather(28.0, 20.0));
        mediaService.setDispatcher(serverError());

        IntentResponse resp = post("packing list");
        assertThat(resp.text()).contains("Список вещей для поездки «Пхукет»");
        assertThat(resp.text()).doesNotContain("Открыть список:");
    }

    // --- LI-c: the list is mirrored onto the note tier so LI-a can check items off ---

    /** No «список вещей» note yet → one is CREATED as a flat type=list checklist + the reply acks it. */
    @Test
    void savesPackingListAsManageableListNote() {
        mcpTravel.setDispatcher(travelStore(tripTo("Пхукет", LocalDate.of(2026, 1, 15))));
        mcpWeather.setDispatcher(weather(28.0, 20.0));

        IntentResponse resp = post("что взять с собой");
        assertThat(resp.text()).contains("Сохранил в список «список вещей»");
        assertThat(noteMethod.get()).as("expected a create (POST)").isEqualTo("POST");
        assertThat(noteBody.get())
                .contains("\"type\":\"list\"")
                .contains("\"title\":\"список вещей\"")
                .contains("- [ ] ");   // a flat CommonMark checklist body
    }

    /** An existing «список вещей» note → the body is REPLACED in place (PUT same id), not duplicated. */
    @Test
    void reAskReplacesExistingListNoteInPlace() {
        mcpTravel.setDispatcher(travelStore(tripTo("Пхукет", LocalDate.of(2026, 1, 15))));
        mcpWeather.setDispatcher(weather(28.0, 20.0));
        UUID existingId = UUID.randomUUID();
        memoryService.setDispatcher(memory(listNote(existingId, "список вещей")));

        IntentResponse resp = post("что взять с собой");
        assertThat(resp.text()).contains("Сохранил в список «список вещей»");
        assertThat(noteMethod.get()).as("expected an update (PUT)").isEqualTo("PUT");
        assertThat(notePath.get()).contains(existingId.toString());
    }

    /** memory-service down → the owner still gets the list + board; only the "saved" ack is dropped. */
    @Test
    void noteWriteSoftFailsToPlainReply() {
        mcpTravel.setDispatcher(travelStore(tripTo("Пхукет", LocalDate.of(2026, 1, 15))));
        mcpWeather.setDispatcher(weather(28.0, 20.0));
        memoryService.setDispatcher(serverError());

        IntentResponse resp = post("что взять с собой");
        assertThat(resp.text()).contains("Список вещей для поездки «Пхукет»")
                .contains("Открыть список:")
                .doesNotContain("Сохранил в список");
    }

    // --- dispatchers ---

    /**
     * mcp-travel: {@code GET /internal/trips/active} returns the given trip (or 204 when null), and
     * {@code GET /internal/travel-profile} always 204 (no profile → the empty-profile default).
     */
    private Dispatcher travelStore(TripDto activeTrip) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                try {
                    if (path != null && path.startsWith("/internal/trips/active")) {
                        return activeTrip == null ? new MockResponse().setResponseCode(204)
                                : jsonResponse(json.writeValueAsString(activeTrip));
                    }
                    if (path != null && path.startsWith("/internal/travel-profile")) {
                        return new MockResponse().setResponseCode(204);
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /** mcp-weather: geocode → a fixed point; climate → a single month with the given temp/precip. */
    private Dispatcher weather(double avgTempC, double precipMm) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                try {
                    if (path != null && path.startsWith("/internal/geocode")) {
                        return jsonResponse(json.writeValueAsString(
                                new GeoLocation("Пхукет", "TH", 7.88, 98.39, "Asia/Bangkok")));
                    }
                    if (path != null && path.startsWith("/internal/climate")) {
                        return jsonResponse(json.writeValueAsString(new ClimateNormals(7.88, 98.39,
                                List.of(new MonthlyNormal(1, avgTempC, precipMm)))));
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /** mcp-weather with an unresolvable place: geocode 204 (no coords) → climate never called. */
    private Dispatcher noGeo() {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(204);
            }
        };
    }

    /**
     * memory-service: {@code GET /v1/notes} returns the given note (or an empty array), and a create
     * ({@code POST /v1/notes}) / replace ({@code PUT /v1/notes/{id}}) captures its method/path/body and
     * echoes a {@link NoteDto}. Stands in for the LI-c list-note upsert.
     */
    private Dispatcher memory(NoteDto existing) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                String method = request.getMethod();
                try {
                    if (path != null && path.startsWith("/v1/notes")) {
                        if ("GET".equals(method)) {
                            return jsonResponse(existing == null ? "[]"
                                    : "[" + json.writeValueAsString(existing) + "]");
                        }
                        // POST (create) or PUT (replace) — capture and echo a note back.
                        noteMethod.set(method);
                        notePath.set(path);
                        noteBody.set(request.getBody().readUtf8());
                        return jsonResponse(json.writeValueAsString(
                                listNote(UUID.randomUUID(), "список вещей")));
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static NoteDto listNote(UUID id, String title) {
        return new NoteDto(id, UUID.randomUUID(), null, title, "list", List.of("list"),
                "travel", null, "- [ ] паспорт", null, Instant.now(), Instant.now());
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

    private static TripDto tripTo(String destination, LocalDate startDate) {
        return new TripDto(UUID.randomUUID(), UUID.randomUUID(), null, "Отпуск", destination,
                startDate, null, "RUB", "active", Instant.now(), Instant.now());
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
