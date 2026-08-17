package dev.fedorov.ailife.agents.travel.flow;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.chart.ChartResult;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.contracts.travelsearch.FlightOffer;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelOffer;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.PlaceResult;
import dev.fedorov.ailife.contracts.weather.ClimateNormals;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.MonthlyNormal;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the trip-planner flow (TR-d) through the agent's HTTP surface ({@code POST
 * /agents/travel/intent}) with a plan-a-trip cue: resolve the profile → one FAST scope extract (named
 * destination + month) → geocode → gather the finance/calendar {@code brief} answers, the destination's
 * climate, and web research in parallel → one {@code trip-composer} synthesis. MockWebServers stand in
 * for mcp-travel, mcp-weather, mcp-web, the orchestrator hub, and llm-gateway; the llm-gateway serves the
 * scope extract then the synthesis (FIFO), and we assert the synthesis request carried every gathered
 * source. Per-source soft-fail and the "still plan when a source is down" behaviour are asserted too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TripComposerTest {

    static MockWebServer mcpTravel;
    static MockWebServer mcpWeather;
    static MockWebServer mcpWeb;
    static MockWebServer travelSearch;
    static MockWebServer orchestrator;
    static MockWebServer llmGateway;
    static MockWebServer mediaService;
    static MockWebServer chartRender;

    @BeforeAll
    static void start() throws Exception {
        mcpTravel = new MockWebServer();
        mcpWeather = new MockWebServer();
        mcpWeb = new MockWebServer();
        travelSearch = new MockWebServer();
        orchestrator = new MockWebServer();
        llmGateway = new MockWebServer();
        mediaService = new MockWebServer();
        chartRender = new MockWebServer();
        mcpTravel.start();
        mcpWeather.start();
        mcpWeb.start();
        travelSearch.start();
        orchestrator.start();
        llmGateway.start();
        mediaService.start();
        chartRender.start();
    }

    @AfterAll
    static void stop() throws Exception {
        mcpTravel.shutdown();
        mcpWeather.shutdown();
        mcpWeb.shutdown();
        travelSearch.shutdown();
        orchestrator.shutdown();
        llmGateway.shutdown();
        mediaService.shutdown();
        chartRender.shutdown();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("travel-agent.mcp-travel-url", () -> "http://localhost:" + mcpTravel.getPort());
        r.add("travel-agent.mcp-weather-url", () -> "http://localhost:" + mcpWeather.getPort());
        r.add("travel-agent.mcp-web-url", () -> "http://localhost:" + mcpWeb.getPort());
        r.add("travel-agent.mcp-travel-search-url", () -> "http://localhost:" + travelSearch.getPort());
        r.add("travel-agent.orchestrator-url", () -> "http://localhost:" + orchestrator.getPort());
        r.add("ailife.llm-client.base-url", () -> "http://localhost:" + llmGateway.getPort());
        r.add("travel-agent.media-service-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("travel-agent.public-media-base-url", () -> "http://localhost:" + mediaService.getPort());
        r.add("travel-agent.mcp-chart-render-url", () -> "http://localhost:" + chartRender.getPort());
    }

    @Autowired WebTestClient http;
    @Autowired ObjectMapper json;

    // Captured inside the dispatchers (takeRequest is unreliable with a custom Dispatcher across the
    // static, context-scoped servers); reset per test.
    static final CopyOnWriteArrayList<String> weatherPaths = new CopyOnWriteArrayList<>();
    static final CopyOnWriteArrayList<String> travelSearchPaths = new CopyOnWriteArrayList<>();
    static final AtomicReference<String> synthBody = new AtomicReference<>();
    static final AtomicBoolean chartRendered = new AtomicBoolean();
    static final AtomicReference<String> chartBody = new AtomicReference<>();
    static final AtomicReference<String> boardBody = new AtomicReference<>();

    @BeforeEach
    void resetCaptures() {
        weatherPaths.clear();
        travelSearchPaths.clear();
        synthBody.set(null);
        chartRendered.set(false);
        chartBody.set(null);
        boardBody.set(null);
        // Default board seam: media-service accepts the upload, chart-render returns a stored image. Tests
        // that assert the render-hiccup fallback override these with an error dispatcher.
        mediaService.setDispatcher(mediaOk());
        chartRender.setDispatcher(chartOk());
    }

    @Test
    void gathersEverySourceAndSynthesizesOnePlan() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mcpTravel.setDispatcher(fixedJson(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62,
                json.readTree("[\"beach\"]"), "family", json.readTree("[4]"),
                new BigDecimal("200000.00"), "RUB", null, Instant.now()))));
        // mcp-weather serves both geocode (destination → coords) and climate (season normals).
        mcpWeather.setDispatcher(pathJson(
                "/internal/geocode", json.writeValueAsString(new GeoLocation("Antalya", "Turkey", 36.9, 30.7, "Europe/Istanbul")),
                "/internal/climate", json.writeValueAsString(new ClimateNormals(36.9, 30.7, List.of(
                        new MonthlyNormal(9, 28.4, 21.0))))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("Турция сезон", List.of(
                new WebSearchHit("Турция в сентябре", "https://example.com/turkey", "Бархатный сезон на море."))))));
        orchestrator.setDispatcher(hubBriefs("Бюджет на отпуск ~250000 RUB, запас есть.", "Свободно 10–20 сентября."));
        // llm-gateway serves the FAST scope extract and the DEFAULT synthesis, told apart by the prompt.
        llmGateway.setDispatcher(llm("{\"destination\":\"Турция\",\"month\":9}",
                "Турция подойдёт: сентябрь тёплый, бюджет в норме. https://example.com/turkey"));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "хочу в Турцию в сентябре, бюджет тысяч 200", List.of(), "telegram", "90", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("Турция подойдёт").contains("https://example.com/turkey");

        // The destination was geocoded, then its climate gathered (the season substrate).
        assertThat(weatherPaths).anyMatch(p -> p.startsWith("/internal/geocode"))
                .anyMatch(p -> p.startsWith("/internal/climate"));

        // The one synthesis turn carried every gathered source + the profile budget hint.
        String body = synthBody.get();
        assertThat(body).as("synthesis request not captured").isNotNull();
        assertThat(body).contains("Бюджет на отпуск")   // finance brief
                .contains("Свободно 10")                 // calendar brief
                .contains("28.4")                        // climate normals
                .contains("example.com/turkey")          // web research link
                .contains("200000");                     // profile budget hint
    }

    @Test
    void financeBriefUnavailableStillPlansUsingHint() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mcpTravel.setDispatcher(fixedJson(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62,
                json.readTree("[\"beach\"]"), "family", null,
                new BigDecimal("200000.00"), "RUB", null, Instant.now()))));
        mcpWeather.setDispatcher(pathJson(
                "/internal/geocode", json.writeValueAsString(new GeoLocation("Antalya", "Turkey", 36.9, 30.7, "Europe/Istanbul")),
                "/internal/climate", json.writeValueAsString(new ClimateNormals(36.9, 30.7, List.of(
                        new MonthlyNormal(9, 28.4, 21.0))))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("Турция", List.of(
                new WebSearchHit("Турция", "https://example.com/turkey", "Море."))))));
        // finance brief errors (ok=false) → dropped; calendar answers.
        orchestrator.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String b = request.getBody().readUtf8();
                if (b.contains("\"targetAgent\":\"finance\"")) {
                    return okJson(AgentActionResult.error("finance offline"));
                }
                return okJson(AgentActionResult.ok(answer("Свободно в сентябре.")));
            }
        });
        llmGateway.setDispatcher(llm("{\"destination\":\"Турция\",\"month\":9}",
                "План готов, бюджет не подтверждён финансистом."));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "хочу в Турцию в сентябре", List.of(), "telegram", "91", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("бюджет не подтверждён");

        // Finance brief dropped from context, but the profile's budget hint still reached the synthesis.
        String body = synthBody.get();
        assertThat(body).as("synthesis request not captured").isNotNull();
        assertThat(body).doesNotContain("Бюджет на отпуск ~250000");
        assertThat(body).contains("200000");
    }

    @Test
    void webSearchUnavailableStillPlans() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mcpTravel.setDispatcher(fixedJson(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62,
                json.readTree("[\"beach\"]"), null, null, null, null, null, Instant.now()))));
        mcpWeather.setDispatcher(pathJson(
                "/internal/geocode", json.writeValueAsString(new GeoLocation("Antalya", "Turkey", 36.9, 30.7, "Europe/Istanbul")),
                "/internal/climate", json.writeValueAsString(new ClimateNormals(36.9, 30.7, List.of(
                        new MonthlyNormal(9, 28.4, 21.0))))));
        mcpWeb.setDispatcher(serverError());   // web research unreachable → dropped
        orchestrator.setDispatcher(hubBriefs("Бюджет ок.", "Даты свободны."));
        llmGateway.setDispatcher(llm("{\"destination\":\"Турция\",\"month\":9}",
                "План по данным бюджета и сезона."));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "хочу в Турцию в сентябре", List.of(), "telegram", "92", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("План по данным бюджета");
    }

    @Test
    void openEndedNoDestinationSkipsClimate() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mcpTravel.setDispatcher(fixedJson(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62,
                json.readTree("[\"beach\"]"), "family", null, null, null, null, Instant.now()))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("пляж куда поехать", List.of(
                new WebSearchHit("Куда на море", "https://example.com/beaches", "Идеи для пляжного отдыха."))))));
        orchestrator.setDispatcher(hubBriefs("Бюджет ок.", "Даты гибкие."));
        // scope has no destination → geocode + climate are skipped entirely.
        llmGateway.setDispatcher(llm("{\"destination\":null,\"month\":null}",
                "Вот несколько идей для пляжа. https://example.com/beaches"));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "хочу на море, куда посоветуешь?", List.of(), "telegram", "93", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).contains("идей для пляжа");

        // No named destination → mcp-weather (geocode + climate) was never called.
        assertThat(weatherPaths).isEmpty();
    }

    @Test
    void boardWithSeasonChartCarriesLinkAndRendersClimateChart() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mcpTravel.setDispatcher(fixedJson(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62,
                json.readTree("[\"beach\"]"), "family", null,
                new BigDecimal("200000.00"), "RUB", null, Instant.now()))));
        // A full multi-month curve → the climate-by-month chart has something to plot.
        mcpWeather.setDispatcher(pathJson(
                "/internal/geocode", json.writeValueAsString(new GeoLocation("Antalya", "Turkey", 36.9, 30.7, "Europe/Istanbul")),
                "/internal/climate", json.writeValueAsString(new ClimateNormals(36.9, 30.7, List.of(
                        new MonthlyNormal(1, 10.0, 190.0), new MonthlyNormal(9, 28.4, 21.0),
                        new MonthlyNormal(12, 12.0, 150.0))))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("Турция сезон", List.of(
                new WebSearchHit("Турция в сентябре", "https://example.com/turkey", "Бархатный сезон."))))));
        orchestrator.setDispatcher(hubBriefs("Бюджет ок.", "Даты свободны."));
        llmGateway.setDispatcher(llm("{\"destination\":\"Турция\",\"month\":9}",
                "Турция подойдёт: сентябрь тёплый."));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "хочу в Турцию в сентябре", List.of(), "telegram", "94", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        // The reply carries the HTML-board open link (the stored media object).
        assertThat(resp.text()).contains("Турция подойдёт")
                .contains("Открыть план поездки:").contains("/v1/media/");
        // A 12-month climate chart was rendered as a line chart for the board.
        assertThat(chartRendered).isTrue();
        assertThat(chartBody.get()).as("chart-render request not captured").isNotNull();
        assertThat(chartBody.get()).contains("\"line\"").contains("28.4");
    }

    @Test
    void renderHiccupFallsBackToTextOnly() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mcpTravel.setDispatcher(fixedJson(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62,
                json.readTree("[\"beach\"]"), "family", null, null, null, null, Instant.now()))));
        mcpWeather.setDispatcher(pathJson(
                "/internal/geocode", json.writeValueAsString(new GeoLocation("Antalya", "Turkey", 36.9, 30.7, "Europe/Istanbul")),
                "/internal/climate", json.writeValueAsString(new ClimateNormals(36.9, 30.7, List.of(
                        new MonthlyNormal(1, 10.0, 190.0), new MonthlyNormal(9, 28.4, 21.0))))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("Турция", List.of(
                new WebSearchHit("Турция", "https://example.com/turkey", "Море."))))));
        orchestrator.setDispatcher(hubBriefs("Бюджет ок.", "Даты свободны."));
        llmGateway.setDispatcher(llm("{\"destination\":\"Турция\",\"month\":9}",
                "План поездки в Турцию готов."));
        // media-service down → the board store fails; the plan must still reach the user, text-only.
        mediaService.setDispatcher(serverError());

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "хочу в Турцию в сентябре", List.of(), "telegram", "95", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        assertThat(resp.text()).isEqualTo("План поездки в Турцию готов.");
        assertThat(resp.text()).doesNotContain("Открыть план поездки:").doesNotContain("/v1/media/");
    }

    @Test
    void liveOptionsRankMinTransfersThenPriceAndFlagOverBudget() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Budget hint 15000 → the direct (30000) and 1-transfer (20000) flights are over, the 2-transfer
        // (10000) is under. Ranking must still put the DIRECT first (min transfers beats price).
        mcpTravel.setDispatcher(fixedJson(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62,
                json.readTree("[\"beach\"]"), "couple", null,
                new BigDecimal("15000.00"), "RUB", null, Instant.now()))));
        mcpWeather.setDispatcher(pathJson(
                "/internal/geocode", json.writeValueAsString(new GeoLocation("Antalya", "Turkey", 36.9, 30.7, "Europe/Istanbul")),
                "/internal/climate", json.writeValueAsString(new ClimateNormals(36.9, 30.7, List.of(
                        new MonthlyNormal(9, 28.4, 21.0))))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("Турция", List.of(
                new WebSearchHit("Турция", "https://example.com/turkey", "Море."))))));
        orchestrator.setDispatcher(hubBriefs("Бюджет ок.", "Даты свободны."));
        travelSearch.setDispatcher(liveSearchOk());
        llmGateway.setDispatcher(llm("{\"destination\":\"Турция\",\"month\":9,\"live\":true}",
                "Вот варианты перелётов и отель."));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "найди билеты в Турцию в сентябре, подбери отель", List.of(), "telegram", "96", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();

        // The live-options context reached the synthesis, ranked min-transfers→price (Direct0 < Mid1 < Cheap2).
        String body = synthBody.get();
        assertThat(body).as("synthesis request not captured").isNotNull();
        int direct = body.indexOf("Direct0");
        int mid = body.indexOf("Mid1");
        int cheap = body.indexOf("Cheap2");
        assertThat(direct).isGreaterThanOrEqualTo(0);
        assertThat(direct).isLessThan(mid);
        assertThat(mid).isLessThan(cheap);
        // Over-budget flagged, not hidden: all three flights reached the synthesis, each with an
        // overBudget flag (the quotes are JSON-escaped inside the request's user-message string).
        assertThat(body).contains("overBudget").contains("Direct0").contains("Mid1").contains("Cheap2");
        assertThat(body).contains("overBudget\\\":true");   // at least one offer marked over the budget

        // The board carries the provider deep links (agent proposes, never books) in ranked order.
        String board = boardBody.get();
        assertThat(board).as("board not captured").isNotNull();
        assertThat(board).contains("aviasales.com/a").contains("aviasales.com/b").contains("aviasales.com/c")
                .contains("hotellook.com/h");
        assertThat(board.indexOf("aviasales.com/b")).isLessThan(board.indexOf("aviasales.com/c"));
        assertThat(board).contains("над бюджетом");

        // Live search actually happened, and no booking/pay path was ever called (there is none).
        assertThat(travelSearchPaths).anyMatch(p -> p.startsWith("/internal/search-flights"))
                .anyMatch(p -> p.startsWith("/internal/search-hotels"))
                .allMatch(p -> p.startsWith("/internal/"));
        assertThat(travelSearchPaths).noneMatch(p -> p.contains("book") || p.contains("pay") || p.contains("order"));
    }

    @Test
    void liveSearchUnconfiguredFallsBackToMvpWithNote() throws Exception {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mcpTravel.setDispatcher(fixedJson(json.writeValueAsString(new TravelProfileDto(
                UUID.randomUUID(), householdId, userId, "Москва", 55.75, 37.62,
                json.readTree("[\"beach\"]"), "family", null, null, null, null, Instant.now()))));
        mcpWeather.setDispatcher(pathJson(
                "/internal/geocode", json.writeValueAsString(new GeoLocation("Antalya", "Turkey", 36.9, 30.7, "Europe/Istanbul")),
                "/internal/climate", json.writeValueAsString(new ClimateNormals(36.9, 30.7, List.of(
                        new MonthlyNormal(9, 28.4, 21.0))))));
        mcpWeb.setDispatcher(fixedJson(json.writeValueAsString(new WebSearchResult("Турция", List.of(
                new WebSearchHit("Турция", "https://example.com/turkey", "Море."))))));
        orchestrator.setDispatcher(hubBriefs("Бюджет ок.", "Даты свободны."));
        // No Travelpayouts key wired → resolve_place degrades → the whole capability is unconfigured.
        travelSearch.setDispatcher(fixedJson(json.writeValueAsString(PlaceResult.unconfigured("Турция"))));
        llmGateway.setDispatcher(llm("{\"destination\":\"Турция\",\"month\":9,\"live\":true}",
                "Турция в сентябре — отличный выбор."));

        NormalizedMessage msg = new NormalizedMessage(userId, householdId, MessageScope.PRIVATE,
                "найди билеты в Турцию в сентябре", List.of(), "telegram", "97", Instant.now());

        IntentResponse resp = post(msg);
        assertThat(resp).isNotNull();
        // The MVP plan still ships, plus a note that live search needs a key.
        assertThat(resp.text()).contains("Турция в сентябре").contains("Travelpayouts");
        // No flights/hotels were searched once resolve reported unconfigured, and nothing was invented.
        assertThat(travelSearchPaths).noneMatch(p -> p.startsWith("/internal/search-flights"));
        assertThat(synthBody.get()).doesNotContain("\"deepLink\"");
    }

    /**
     * mcp-travel-search: resolve both places (branch on the query), then serve three unsorted flights
     * (a direct, a 1-transfer, a 2-transfer) + one hotel, each with a provider deep link.
     */
    private Dispatcher liveSearchOk() {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                String reqBody = request.getBody().readUtf8();
                if (path != null) travelSearchPaths.add(path);
                try {
                    if (path != null && path.startsWith("/internal/resolve-place")) {
                        PlaceResult pr = reqBody.contains("Москва")
                                ? new PlaceResult("Москва", "Moscow", "MOW", null, false)
                                : new PlaceResult("Турция", "Turkey", "AYT", "12209", false);
                        return jsonResponse(json.writeValueAsString(pr));
                    }
                    if (path != null && path.startsWith("/internal/search-flights")) {
                        return jsonResponse(json.writeValueAsString(new FlightSearchResult(false, List.of(
                                new FlightOffer(10000.0, "RUB", 2, "Cheap2", "2026-09", null, "https://www.aviasales.com/a"),
                                new FlightOffer(30000.0, "RUB", 0, "Direct0", "2026-09", null, "https://www.aviasales.com/b"),
                                new FlightOffer(20000.0, "RUB", 1, "Mid1", "2026-09", null, "https://www.aviasales.com/c")))));
                    }
                    if (path != null && path.startsWith("/internal/search-hotels")) {
                        return jsonResponse(json.writeValueAsString(new HotelSearchResult(false, List.of(
                                new HotelOffer("Rixos", 12000.0, "RUB", 5.0, "https://search.hotellook.com/h")))));
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private IntentResponse post(NormalizedMessage msg) {
        return http.post().uri("/agents/travel/intent")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(msg)
                .exchange().expectStatus().isOk()
                .expectBody(IntentResponse.class).returnResult().getResponseBody();
    }

    private ObjectNode answer(String text) {
        ObjectNode node = json.createObjectNode();
        node.put("agent", "x");
        node.put("answer", text);
        return node;
    }

    /**
     * llm-gateway: three turns now (#475). The {@link dev.fedorov.ailife.agents.travel.intent.TravelIntentRouter}
     * classify turn comes first (detected by the router prompt marker) → route to {@code trip-composer}; then
     * the FAST scope extract (its own prompt marker); everything else is the synthesis.
     */
    private Dispatcher llm(String scopeJson, String synthesisText) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String b = request.getBody().readUtf8();
                if (b.contains("routing a message for the travel agent")) {
                    return content("{\"action\":\"skill\",\"name\":\"trip-composer\"}");
                }
                boolean isScope = b.contains("extract trip parameters");
                if (!isScope) {
                    synthBody.set(b);   // capture the synthesis request for assertions
                }
                return content(isScope ? scopeJson : synthesisText);
            }

            private MockResponse content(String text) {
                try {
                    return jsonResponse(json.writeValueAsString(new LlmChatResponse(
                            "mock", text, "stop", new LlmUsage(50, 20, 70))));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /** The orchestrator hub answers both briefs; branch on the request's targetAgent. */
    private Dispatcher hubBriefs(String financeAnswer, String calendarAnswer) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String b = request.getBody().readUtf8();
                String a = b.contains("\"targetAgent\":\"finance\"") ? financeAnswer : calendarAnswer;
                return okJson(AgentActionResult.ok(answer(a)));
            }
        };
    }

    private MockResponse okJson(AgentActionResult result) {
        try {
            return jsonResponse(json.writeValueAsString(result));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Dispatcher fixedJson(String body) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return jsonResponse(body);
            }
        };
    }

    /** Dispatch two JSON bodies by request path (for mcp-weather's geocode + climate on one server). */
    private static Dispatcher pathJson(String pathA, String bodyA, String pathB, String bodyB) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path != null) weatherPaths.add(path);
                if (path != null && path.startsWith(pathA)) return jsonResponse(bodyA);
                if (path != null && path.startsWith(pathB)) return jsonResponse(bodyB);
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    /** media-service accepts the board/chart upload and returns a stored object with a fresh id. */
    private Dispatcher mediaOk() {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                boardBody.set(request.getBody().readUtf8());   // capture the rendered board for assertions
                try {
                    return jsonResponse(json.writeValueAsString(new MediaObjectDto(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "file",
                            "text/html", 1024, "sha", "travel", Instant.now())));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /** chart-render returns a stored image id and records that (and what) it was asked to plot. */
    private Dispatcher chartOk() {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                chartRendered.set(true);
                chartBody.set(request.getBody().readUtf8());
                try {
                    return jsonResponse(json.writeValueAsString(new ChartResult(UUID.randomUUID(), "java2d")));
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
}
