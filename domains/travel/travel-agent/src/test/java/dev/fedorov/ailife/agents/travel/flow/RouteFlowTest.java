package dev.fedorov.ailife.agents.travel.flow;

import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.agentruntime.transparency.DegradedNotice;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.travel.RouteDto;
import dev.fedorov.ailife.contracts.travel.TripDto;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the route-import flow (RT-c) through the agent's HTTP surface ({@code POST /agents/travel/intent})
 * with a {@code file} attachment. MockWebServers stand in for media-service (GET the file bytes back + the
 * board upload) and mcp-travel ({@code /internal/trips/active} + {@code /internal/routes}). No llm-gateway —
 * the format is sniffed from the bytes, not classified by an LLM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class RouteFlowTest {

    private static final UUID TRIP_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String MEDIA_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String GPX = """
            <?xml version="1.0"?><gpx version="1.1"><metadata><name>Прогулка</name></metadata>
            <trk><trkseg><trkpt lat="55.75" lon="37.62"/><trkpt lat="55.76" lon="37.63"/></trkseg></trk></gpx>
            """;

    static MockWebServer mcpTravel;
    static MockWebServer mediaService;
    static MockWebServer llm;

    @BeforeAll
    static void start() throws Exception {
        mcpTravel = new MockWebServer();
        mediaService = new MockWebServer();
        llm = new MockWebServer();
        mcpTravel.start();
        mediaService.start();
        llm.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpTravel.shutdown();
        mediaService.shutdown();
        llm.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("travel-agent.mcp-travel-url", () -> "http://localhost:" + mcpTravel.getPort());
        r.add("travel-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("travel-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        // Unused by the route flow, but the context builds their WebClient beans — point them anywhere.
        r.add("travel-agent.mcp-weather-url", () -> "http://localhost:1");
        r.add("travel-agent.mcp-web-url", () -> "http://localhost:1");
        r.add("travel-agent.mcp-travel-search-url", () -> "http://localhost:1");
        r.add("travel-agent.orchestrator-url", () -> "http://localhost:1");
        r.add("travel-agent.mcp-chart-render-url", () -> "http://localhost:1");
        r.add("travel-agent.memory-service-url", () -> "http://localhost:1");
        // #475: a non-attachment message goes through the router classify turn; a bare map link is a "chat"
        // decision → the router's fallback runs the map-link import. Attachment tests bypass the router.
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llm.getPort());
        r.add("spring.ai.mcp.client.enabled", () -> "false");
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    static final CopyOnWriteArrayList<String> travelPaths = new CopyOnWriteArrayList<>();
    static final AtomicReference<String> routeReqBody = new AtomicReference<>();
    static final AtomicReference<String> boardBody = new AtomicReference<>();
    static final AtomicReference<byte[]> fileBytes = new AtomicReference<>();

    @BeforeEach
    void reset() {
        travelPaths.clear();
        routeReqBody.set(null);
        boardBody.set(null);
        fileBytes.set(GPX.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mediaService.setDispatcher(media(false));
        llm.setDispatcher(routerChat());   // #475: the router classify turn → chat → map-link fallback runs
    }

    @Test
    void importsAttachedRouteToActiveTrip() {
        mcpTravel.setDispatcher(travelStore(true));

        IntentResponse resp = postFile();
        assertThat(resp.text()).contains("Импортировал маршрут «Прогулка»")
                .contains("Добавил к поездке «Тайланд»")
                .contains("На карте: https://www.openstreetmap.org")
                .contains("Открыть на доске:");
        // The route was stored with the active trip id + gpx format.
        assertThat(travelPaths).anyMatch(p -> p.equals("/internal/routes"));
        assertThat(routeReqBody.get()).contains("\"format\":\"gpx\"").contains(TRIP_ID.toString());
        // The board carries the route section + the map link.
        assertThat(boardBody.get()).isNotNull();
        assertThat(boardBody.get()).contains("Маршрут").contains("openstreetmap.org");
    }

    @Test
    void importsWithoutActiveTripUnattached() {
        mcpTravel.setDispatcher(travelStore(false)); // no active trip → getActiveTrip 204

        IntentResponse resp = postFile();
        assertThat(resp.text()).contains("Импортировал маршрут «Прогулка»");
        assertThat(resp.text()).doesNotContain("Добавил к поездке");
        // Imported unattached: tripId is null in the request.
        assertThat(routeReqBody.get()).doesNotContain(TRIP_ID.toString());
    }

    @Test
    void unknownFormatIsRejectedWithoutStoring() {
        fileBytes.set("this is not a route file".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mcpTravel.setDispatcher(travelStore(true));

        IntentResponse resp = postFile();
        assertThat(resp.text()).contains("Не смог распознать формат маршрута");
        assertThat(travelPaths).noneMatch(p -> p.equals("/internal/routes"));
    }

    @Test
    void importsMapLinkToActiveTrip() {
        mcpTravel.setDispatcher(travelStore(true));

        IntentResponse resp = postText("вот это место: https://yandex.ru/maps/?ll=37.62,55.75&z=13");
        assertThat(resp.text()).contains("Импортировал маршрут")
                .contains("Добавил к поездке «Тайланд»")
                .contains("На карте: https://www.openstreetmap.org");
        assertThat(travelPaths).anyMatch(p -> p.equals("/internal/routes"));
        assertThat(routeReqBody.get()).contains("\"format\":\"maplink\"")
                .contains("yandex.ru/maps").contains(TRIP_ID.toString());
    }

    @Test
    void shortMapLinkGivesFriendlyMessage() {
        mcpTravel.setDispatcher(travelStore(true)); // returns 400 for a goo.gl body (no coordinates)

        IntentResponse resp = postText("смотри https://maps.app.goo.gl/abc123");
        assertThat(resp.text()).contains("Не смог разобрать ссылку").contains("файл маршрута");
    }

    @Test
    void boardHiccupFallsBackToTextOnly() {
        mcpTravel.setDispatcher(travelStore(true));
        mediaService.setDispatcher(media(true)); // board upload 500 → text-only

        IntentResponse resp = postFile();
        assertThat(resp.text()).contains("Импортировал маршрут «Прогулка»").contains("На карте:");
        // Honest about the missing board (#485), and no fake board link.
        assertThat(resp.text()).contains(DegradedNotice.MARKER).doesNotContain("Открыть на доске:");
    }

    // --- dispatchers ---

    /** llm-gateway: the router classify turn (#475) for a non-attachment message → a chat decision, so the
     *  {@code TravelIntentRouter} falls back to the deterministic map-link import. */
    private Dispatcher routerChat() {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    return jsonResponse(json.writeValueAsString(new LlmChatResponse(
                            "mock", "{\"action\":\"chat\"}", "stop", new LlmUsage(10, 5, 15))));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /** mcp-travel: GET /internal/trips/active (toggle) + POST /internal/routes (echo a stored RouteDto). */
    private Dispatcher travelStore(boolean hasActiveTrip) {
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
                    if ("POST".equals(method) && path != null && path.equals("/internal/routes")) {
                        routeReqBody.set(body);
                        // A short link carries no coordinates → the store returns 400 (parser found no points).
                        if (body.contains("goo.gl")) {
                            return new MockResponse().setResponseCode(400)
                                    .setBody("{\"error\":\"Route has no points (no track and no waypoints)\"}");
                        }
                        UUID tripId = body.contains(TRIP_ID.toString()) ? TRIP_ID : null;
                        return jsonResponse(json.writeValueAsString(route(tripId)));
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /** media-service: GET /v1/media/{id} → the file bytes; POST /v1/media → board upload (or 500). */
    private Dispatcher media(boolean boardFails) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String method = request.getMethod();
                if ("GET".equals(method)) {
                    return new MockResponse()
                            .setHeader("content-type", "application/octet-stream")
                            .setBody(new okio.Buffer().write(fileBytes.get()));
                }
                // POST /v1/media → board upload.
                boardBody.set(request.getBody().readUtf8());
                if (boardFails) {
                    return new MockResponse().setResponseCode(500);
                }
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

    private static TripDto trip() {
        return new TripDto(TRIP_ID, UUID.randomUUID(), null, "Тайланд", "Пхукет", null, null,
                "RUB", "active", Instant.now(), Instant.now());
    }

    private RouteDto route(UUID tripId) {
        var geometry = json.createObjectNode();
        var track = geometry.putArray("track");
        track.addObject().put("lat", 55.75).put("lon", 37.62);
        track.addObject().put("lat", 55.76).put("lon", 37.63);
        geometry.putArray("waypoints");
        return new RouteDto(UUID.randomUUID(), UUID.randomUUID(), tripId, "Прогулка", "gpx", 2,
                new BigDecimal("1234.5"), geometry, Instant.now());
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("content-type", "application/json").setBody(body);
    }

    private IntentResponse postFile() {
        NormalizedMessage msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(),
                MessageScope.PRIVATE, "вот наш маршрут",
                List.of(new Attachment("file", "application/gpx+xml", MEDIA_ID, "route.gpx")),
                "telegram", "1", Instant.now());
        return post(msg);
    }

    private IntentResponse postText(String text) {
        NormalizedMessage msg = new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(),
                MessageScope.PRIVATE, text, List.of(), "telegram", "1", Instant.now());
        return post(msg);
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/travel/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }
}
