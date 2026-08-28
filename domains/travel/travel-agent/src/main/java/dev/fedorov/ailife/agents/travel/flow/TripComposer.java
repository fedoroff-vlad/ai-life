package dev.fedorov.ailife.agents.travel.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.transparency.DegradedNotice;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agentruntime.http.ChartRenderClient;
import dev.fedorov.ailife.agents.travel.http.ClimateClient;
import dev.fedorov.ailife.agentruntime.http.GeocodeClient;
import dev.fedorov.ailife.agents.travel.http.TravelProfileClient;
import dev.fedorov.ailife.profile.ProfileScopeResolver;
import dev.fedorov.ailife.agents.travel.http.TravelSearchClient;
import dev.fedorov.ailife.agentruntime.http.WebSearchClient;
import dev.fedorov.ailife.contracts.travelsearch.FlightOffer;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelOffer;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.PlaceResult;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.chart.ChartSeries;
import dev.fedorov.ailife.contracts.chart.ChartSpec;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.docrender.Doc;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The trip-planning flow (TR-d): the travel agent's reason for existing, and — like briefing's
 * {@code BriefingComposer} — a multi-domain <b>read</b> coordinator. Given a plan-a-trip wish it
 * (1) <b>resolves</b> the requester's {@code travel_profile} (self → household-default → an empty
 * default), (2) runs one cheap <b>{@link LlmChannel#FAST}</b> scope extract to spot a named destination +
 * a stated month, geocoding the destination when present, then (3) <b>gathers</b> in parallel over the
 * shared {@link Coordinator}: the <b>budget</b> and <b>free dates</b> as read-only cross-agent
 * {@code brief} answers from the finance and calendar agents (through the orchestrator hub — agents never
 * call each other directly), the destination's <b>season fit</b> from {@code mcp-weather}'s climate
 * normals, and qualitative <b>destination research</b> from {@code mcp-web} search; then (4) folds them
 * into a {@code context} and runs <b>one</b> {@code trip-composer} <b>{@link LlmChannel#DEFAULT}</b>
 * synthesis into a concise plan — route, season verdict, budget check — grounded in the web links.
 *
 * <p><b>Per-source soft-fail</b> is built into the Coordinator: a slow/broken/absent source is simply
 * omitted, never faked. A missing finance brief falls back to the profile's {@code budgetHint} (marked
 * unverified); no named destination skips the climate gather. <b>Booking boundary (ADR-0003):</b> the
 * plan proposes options + provenance links only — it never books, reserves, or pays (enforced by the
 * {@code trip-composer} SKILL; this flow makes no outbound booking call).
 *
 * <p><b>TR-f2 (live options):</b> when the FAST scope spots that the owner wants concrete tickets/hotels
 * ({@code live}) for a named destination + month, the gather grows a live-options step over the shared
 * {@code mcp-travel-search} capability: resolve the destination (+ home-base origin) to search codes,
 * search flights + hotels, <b>rank flights min-transfers→price</b>, flag any over the budget hint (never
 * hidden), and fold the ranked options + provider <b>deep links</b> into both the synthesis context and the
 * board. The capability is <b>owner-key-gated</b>: with no Travelpayouts key it reports {@code unconfigured}
 * and the planner <b>degrades to the MVP plan</b> + tells the owner live search isn't set up. Still
 * <b>never books</b> (ADR-0003) — options + links only.
 *
 * <p><b>TR-e (the MVP closer):</b> the synthesis is then rendered to an <b>HTML travel board</b> — the
 * plan text as a section, the gathered web sources as grounded provenance links, and the destination's
 * <b>climate-by-month curve</b> as a chart (rendered by the shared {@code mcp-chart-render} capability) —
 * via the shared {@link DeliverablePublisher} (render → store in media-service → link), with the open-link
 * appended to the reply. Both the chart and the board are <b>soft-failed</b>: a board render/store hiccup
 * ships the text-only plan with a discreet ⚠️ degraded-state notice (#485), not a silent full-looking
 * reply. Same board seam as briefing/finance.
 */
@Component
public class TripComposer {

    private static final Logger log = LoggerFactory.getLogger(TripComposer.class);
    private static final String SKILL_NAME = "trip-composer";
    private static final String BRIEF_ACTION = "brief";
    private static final String FINANCE_AGENT = "finance";
    private static final String CALENDAR_AGENT = "calendar";
    /** A {@code brief} fronts a FAST synthesis on the specialist side, so the passthrough default is too tight. */
    private static final Duration BRIEF_TIMEOUT = Duration.ofSeconds(20);
    /** Bound the research fan-out so the plan stays cheap + fast. */
    private static final int MAX_RESEARCH = 6;
    /** Cap the board's provenance link list (the gathered web research). */
    private static final int MAX_LINKS = 8;
    /** Cap the live flight/hotel options folded into the synthesis + board (TR-f2). */
    private static final int MAX_FLIGHT_OPTIONS = 5;
    private static final int MAX_HOTEL_OPTIONS = 5;
    /** A default stay length (nights) when the owner states only a month, not exact dates. */
    private static final int DEFAULT_STAY_NIGHTS = 7;
    /** Told to the owner when live search is asked for but the capability has no Travelpayouts key. */
    private static final String LIVE_UNCONFIGURED_NOTE =
            "Живой поиск билетов и отелей пока не настроен (нужен бесплатный ключ Travelpayouts) — "
            + "показал план без конкретных цен.";
    /** Russian month labels for the climate-by-month chart's x-axis (index = month - 1). */
    private static final String[] MONTHS_RU = {
            "Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек"};

    /** Cheap FAST pre-step: spot a concrete destination + month + whether live options are wanted. */
    private static final String SCOPE_SYSTEM = """
            You extract trip parameters from a short vacation request. Return ONLY a JSON object:
            {"destination": <a place the person explicitly named, or null>, "month": <trip month 1-12, or null>,
             "live": <true if the person asks for concrete tickets/flights/hotels or their prices, else false>}.
            "destination" is a concrete place (city / country / region) ONLY if the person named one — a
            generic wish like "на море" or "somewhere warm" is NOT a destination, use null. "month" is the
            travel month if stated (by name or number), else null. "live" is true when the person wants real
            bookable options — "найди билеты", "подбери отель", "сколько стоит перелёт", "find flights/hotels" —
            and false for a general "where should I go / plan a trip" wish. Output only the JSON — no prose, no code fence.""";

    private final Coordinator coordinator;
    private final TravelProfileClient profiles;
    private final ProfileScopeResolver profileScope;
    private final GeocodeClient geocode;
    private final ClimateClient climate;
    private final WebSearchClient web;
    private final TravelSearchClient search;
    private final OrchestratorInvokeClient hub;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final DeliverablePublisher publisher;
    private final ChartRenderClient chartRender;

    public TripComposer(Coordinator coordinator, TravelProfileClient profiles,
                        ProfileScopeResolver profileScope, GeocodeClient geocode,
                        ClimateClient climate, WebSearchClient web, TravelSearchClient search,
                        OrchestratorInvokeClient hub, LlmClient llm, SkillRegistry skills,
                        AgentManifest manifest, ObjectMapper json, DeliverablePublisher publisher,
                        ChartRenderClient chartRender) {
        this.coordinator = coordinator;
        this.profiles = profiles;
        this.profileScope = profileScope;
        this.geocode = geocode;
        this.climate = climate;
        this.web = web;
        this.search = search;
        this.hub = hub;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.publisher = publisher;
        this.chartRender = chartRender;
    }

    public Mono<IntentResponse> plan(NormalizedMessage msg) {
        return resolveProfile(msg)
                .flatMap(profile -> extractScope(msg.text())
                        .flatMap(scope -> resolveDestination(scope)
                                .flatMap(geo -> compose(msg, profile, scope, geo))))
                .onErrorResume(e -> {
                    log.warn("trip plan failed: {}", e.toString());
                    return Mono.just(reply("Не удалось спланировать поездку. Попробуйте позже.", null));
                });
    }

    /**
     * self → own household-default → family/shared household-default → an empty default (ADR-0005): the
     * shared {@link ProfileScopeResolver} rule; a member who set nothing now inherits the family's home
     * base + rest-types (#490 FO-3). A broken mcp-travel/profile-service soft-fails to the empty default.
     */
    private Mono<TravelProfileDto> resolveProfile(NormalizedMessage msg) {
        return profileScope.<TravelProfileDto>resolve(msg.userId(), msg.householdId(), profiles::get)
                .switchIfEmpty(Mono.fromSupplier(() -> emptyProfile(msg)))
                .onErrorResume(e -> {
                    log.warn("travel profile resolve failed, using defaults: {}", e.toString());
                    return Mono.just(emptyProfile(msg));
                });
    }

    /** One FAST turn extracting {destination?, month?}; any failure → an empty scope (no destination, no month). */
    private Mono<Scope> extractScope(String text) {
        if (text == null || text.isBlank()) {
            return Mono.just(Scope.EMPTY);
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.FAST, List.of(
                LlmMessage.system(SCOPE_SYSTEM), LlmMessage.user(text)), 0.0);
        return llm.chat(request)
                .map(r -> parseScope(r.content()))
                .onErrorResume(e -> {
                    log.warn("trip scope extract failed, planning open-ended: {}", e.toString());
                    return Mono.just(Scope.EMPTY);
                });
    }

    /** Geocode a named destination so climate can be gathered for it; none / hiccup → no coordinates. */
    private Mono<GeoLocation> resolveDestination(Scope scope) {
        if (scope.destination() == null || scope.destination().isBlank()) {
            return Mono.just(NO_GEO);
        }
        return geocode.geocode(scope.destination(), null).defaultIfEmpty(NO_GEO);
    }

    private Mono<IntentResponse> compose(NormalizedMessage msg, TravelProfileDto profile, Scope scope, GeoLocation geo) {
        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("budget", brief(FINANCE_AGENT, msg, budgetQuestion(msg, profile)));
        gather.put("dates", brief(CALENDAR_AGENT, msg, datesQuestion(msg, scope)));
        if (geo.latitude() != null && geo.longitude() != null) {
            // Fetch the full 12-month curve (null month): it grounds the synthesis's season verdict (the
            // requested month is in the payload) and is the TR-e board's climate-by-month chart.
            gather.put("climate", climate.climate(geo.latitude(), geo.longitude(), null)
                    .map(json::valueToTree));
        }
        gather.put("research", web.search(researchQuery(msg, profile, scope), MAX_RESEARCH)
                .map(res -> json.valueToTree(res.hits())));
        // TR-f2 live options: only when the owner asked for concrete tickets/hotels AND named a
        // destination + month (a real search needs both). Owner-key-gated / soft-fail inside; per-source
        // soft-fail keeps a missing step from sinking the plan.
        if (scope.live() && scope.destination() != null && !scope.destination().isBlank() && scope.month() != null) {
            gather.put("liveOptions", liveOptions(profile, scope));
        }

        ObjectNode payload = json.createObjectNode();
        payload.put("userText", msg.text() == null ? "" : msg.text());
        if (scope.month() != null) {
            payload.put("month", scope.month());
        }
        if (scope.destination() != null && !scope.destination().isBlank()) {
            payload.put("destination", scope.destination());
        }
        payload.set("profile", profileNode(profile));

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .flatMap(r -> {
                    // TR-f2: if live search was asked for but the capability has no key, tell the owner and
                    // fall back to the MVP plan (the synthesis already ran without live options).
                    String text = withLiveNote(r.text(), r.gathered());
                    return publishBoard(msg, scope, text, r.gathered())
                            .map(link -> reply(withLink(text, link), r.llmModel()))
                            .defaultIfEmpty(reply(text, r.llmModel()))
                            .onErrorResume(e -> {
                                // A media/render hiccup must not sink the plan — hand back the text, but say
                                // the board is missing rather than pretend a full delivery (#485).
                                log.warn("trip board store failed: {}", e.toString());
                                return Mono.just(reply(DegradedNotice.append(text,
                                        "не смог собрать HTML-доску плана сейчас — показал план только текстом, попробуйте позже"),
                                        r.llmModel()));
                            });
                })
                .onErrorResume(e -> {
                    log.warn("trip synthesis failed: {}", e.toString());
                    return Mono.just(reply("Собрал данные, но не смог оформить план поездки. Попробуйте позже.", null));
                });
    }

    /**
     * Render the synthesized plan (+ the destination's climate-by-month chart + the gathered web sources
     * as provenance) to an HTML travel board and store it, returning the public open-link (TR-e). The
     * chart is soft-failed independently, so a chart-render hiccup still ships a board without it.
     */
    private Mono<String> publishBoard(NormalizedMessage msg, Scope scope, String text, JsonNode gathered) {
        return chartUrl(msg, scope, gathered).flatMap(chartUrl -> {
            String dest = scope.destination();
            Doc.Builder b = Doc.builder("План поездки")
                    .kicker(dest == null || dest.isBlank() ? "Путешествие · План" : "Путешествие · " + dest)
                    .subtitle(boardSubtitle(scope));
            if (!chartUrl.isBlank()) {
                b.chart(chartUrl);
            }
            b.section("План", DeliverablePublisher.splitParagraphs(text));
            // TR-f2: the live flight/hotel options as buy deep links (agent never books) — ranked, with an
            // "над бюджетом" marker on over-budget offers. Listed before the qualitative research links.
            for (Doc.LinkItem l : optionLinks(gathered)) {
                b.link(l.label(), l.url(), l.note());
            }
            for (Doc.LinkItem l : researchLinks(gathered)) {
                b.link(l.label(), l.url(), l.note());
            }
            return publisher.publish(msg.householdId(), msg.userId(), b.build());
        });
    }

    /**
     * Render the destination's climate-by-month curve via the shared {@code mcp-chart-render} capability
     * and return the public URL of the stored image. Soft-fail: nothing to plot, or any render/store
     * failure, yields an empty string so the board still ships without the chart.
     */
    private Mono<String> chartUrl(NormalizedMessage msg, Scope scope, JsonNode gathered) {
        ChartSpec spec = climateChartSpec(gathered, scope.destination());
        if (spec == null) {
            return Mono.just("");
        }
        return chartRender.render(msg.householdId(), msg.userId(), spec)
                .map(r -> {
                    String url = publisher.mediaUrl(r.mediaId());
                    return url == null ? "" : url;
                })
                .onErrorResume(e -> {
                    log.warn("trip climate chart render failed: {}", e.toString());
                    return Mono.just("");
                });
    }

    /** A line chart of the destination's average monthly temperature; null when there's no curve to plot. */
    private ChartSpec climateChartSpec(JsonNode gathered, String destination) {
        if (gathered == null) {
            return null;
        }
        JsonNode months = gathered.path("climate").path("months");
        if (!months.isArray() || months.isEmpty()) {
            return null;
        }
        List<String> categories = new ArrayList<>();
        List<Double> temps = new ArrayList<>();
        for (JsonNode m : months) {
            JsonNode monthNode = m.get("month");
            if (monthNode == null || !monthNode.isNumber() || !m.hasNonNull("avgTempC")) {
                continue;
            }
            int month = monthNode.asInt();
            if (month < 1 || month > 12) {
                continue;
            }
            categories.add(MONTHS_RU[month - 1]);
            temps.add(m.get("avgTempC").asDouble());
        }
        if (temps.size() < 2) {
            return null;   // a single point isn't a curve
        }
        String title = (destination == null || destination.isBlank())
                ? "Климат по месяцам · °C" : "Климат: " + destination + " · °C по месяцам";
        return new ChartSpec("line", title, categories,
                List.of(new ChartSeries("Средняя °C", temps)), "°C");
    }

    /** Flatten the gathered {@code research} hits into a deduped, capped provenance link list for the board. */
    private List<Doc.LinkItem> researchLinks(JsonNode gathered) {
        List<Doc.LinkItem> links = new ArrayList<>();
        if (gathered == null) {
            return links;
        }
        JsonNode hits = gathered.get("research");
        if (hits == null || !hits.isArray()) {
            return links;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode hit : hits) {
            String url = hit.hasNonNull("url") ? hit.get("url").asString() : null;
            if (url == null || url.isBlank() || !seen.add(url)) {
                continue;
            }
            String title = hit.hasNonNull("title") ? hit.get("title").asString() : url;
            String snippet = hit.hasNonNull("snippet") ? hit.get("snippet").asString() : null;
            links.add(new Doc.LinkItem(title, url, snippet));
            if (links.size() >= MAX_LINKS) {
                break;
            }
        }
        return links;
    }

    /**
     * Flatten the gathered {@code liveOptions} (TR-f2) into board deep links — flights first (already ranked
     * min-transfers→price), then hotels — each an option the owner opens on the provider to buy. Over-budget
     * offers are kept, marked "над бюджетом" in the note (flag, never hide). The agent never books.
     */
    private List<Doc.LinkItem> optionLinks(JsonNode gathered) {
        List<Doc.LinkItem> links = new ArrayList<>();
        if (gathered == null) {
            return links;
        }
        JsonNode live = gathered.get("liveOptions");
        if (live == null || live.path("unconfigured").asBoolean(false)) {
            return links;
        }
        JsonNode flights = live.get("flights");
        if (flights != null && flights.isArray()) {
            int i = 1;
            for (JsonNode f : flights) {
                String url = f.path("deepLink").asString(null);
                if (url == null || url.isBlank()) {
                    continue;
                }
                links.add(new Doc.LinkItem(flightLabel(f, i++), url, priceNote(f)));
            }
        }
        JsonNode hotels = live.get("hotels");
        if (hotels != null && hotels.isArray()) {
            for (JsonNode h : hotels) {
                String url = h.path("deepLink").asString(null);
                if (url == null || url.isBlank()) {
                    continue;
                }
                String name = h.path("name").asString("Отель");
                links.add(new Doc.LinkItem("Отель: " + name, url, priceNote(h)));
            }
        }
        return links;
    }

    private static String flightLabel(JsonNode f, int idx) {
        StringBuilder sb = new StringBuilder("Перелёт ").append(idx);
        if (f.hasNonNull("transfers")) {
            int t = f.get("transfers").asInt();
            sb.append(" · ").append(t == 0 ? "без пересадок" : t + " пересадк.");
        }
        if (f.hasNonNull("airline")) {
            sb.append(" · ").append(f.get("airline").asString());
        }
        return sb.toString();
    }

    /** A short price note with an over-budget marker — never a claim of a booked/guaranteed price. */
    private static String priceNote(JsonNode offer) {
        StringBuilder sb = new StringBuilder();
        if (offer.hasNonNull("price")) {
            sb.append("от ").append(offer.get("price").asString());
            if (offer.hasNonNull("currency")) {
                sb.append(' ').append(offer.get("currency").asString());
            }
        }
        if (offer.path("overBudget").asBoolean(false)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("над бюджетом");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String boardSubtitle(Scope scope) {
        StringBuilder sb = new StringBuilder("Маршрут · сезон · бюджет");
        if (scope.month() != null && scope.month() >= 1 && scope.month() <= 12) {
            sb.append(" · ").append(MONTHS_RU[scope.month() - 1]);
        }
        return sb.toString();
    }

    private static String withLink(String text, String link) {
        if (link == null || link.isBlank()) {
            return text;
        }
        return text + "\n\nОткрыть план поездки: " + link;
    }

    /**
     * TR-f2 live-options gather: resolve the destination (and the home-base origin) to search codes, then
     * search flights + hotels for the stated month, ranking flights by <b>min transfers then price</b> and
     * flagging any offer over the owner's budget hint (never hiding it). Returns a compact
     * {@code {unconfigured, budgetRef, flights[], hotels[]}} node folded into the synthesis context + the
     * board. <b>Owner-key-gated:</b> when the capability reports {@code unconfigured} (no Travelpayouts key)
     * the caller degrades to the MVP plan. <b>Never books</b> (ADR-0003) — each offer is a provider deep
     * link only. Soft-fails to an empty Mono so a search hiccup drops only this step.
     */
    private Mono<JsonNode> liveOptions(TravelProfileDto profile, Scope scope) {
        return search.resolvePlace(scope.destination())
                .flatMap(dest -> {
                    if (dest.unconfigured()) {
                        return Mono.just(unconfiguredOptions());
                    }
                    Mono<PlaceResult> originMono = (profile.homeBaseLabel() == null || profile.homeBaseLabel().isBlank())
                            ? Mono.just(NO_PLACE)
                            : search.resolvePlace(profile.homeBaseLabel());
                    return originMono.flatMap(origin -> {
                        int party = partySize(profile);
                        String departMonth = monthCode(scope.month());
                        Mono<FlightSearchResult> flights = canSearchFlights(origin, dest)
                                ? search.searchFlights(origin.iataCity(), dest.iataCity(), departMonth, null, party)
                                : Mono.just(new FlightSearchResult(false, List.of()));
                        String[] stay = stayDates(scope.month());
                        String hotelLocation = dest.hotelLocationId() != null ? dest.hotelLocationId() : scope.destination();
                        Mono<HotelSearchResult> hotels = search.searchHotels(hotelLocation, stay[0], stay[1], party);
                        return Mono.zip(flights, hotels)
                                .map(t -> optionsNode(t.getT1(), t.getT2(), budgetRef(profile)));
                    });
                })
                .onErrorResume(e -> {
                    log.warn("live travel search failed, degrading to MVP plan: {}", e.toString());
                    return Mono.empty();
                });
    }

    private static boolean canSearchFlights(PlaceResult origin, PlaceResult dest) {
        return origin.iataCity() != null && !origin.iataCity().isBlank()
                && dest.iataCity() != null && !dest.iataCity().isBlank();
    }

    /** Build the {@code liveOptions} context node: flights ranked min-transfers→price, hotels by price. */
    private JsonNode optionsNode(FlightSearchResult flights, HotelSearchResult hotels, BigDecimal budgetRef) {
        ObjectNode node = json.createObjectNode();
        node.put("unconfigured", false);
        if (budgetRef != null) {
            node.put("budgetRef", budgetRef);
        }
        ArrayNode flightArr = node.putArray("flights");
        flights.offers().stream()
                .sorted(FLIGHT_ORDER)
                .limit(MAX_FLIGHT_OPTIONS)
                .forEach(o -> flightArr.add(flightNode(o, budgetRef)));
        ArrayNode hotelArr = node.putArray("hotels");
        hotels.offers().stream()
                .sorted(Comparator.comparing(HotelOffer::price, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_HOTEL_OPTIONS)
                .forEach(o -> hotelArr.add(hotelNode(o, budgetRef)));
        return node;
    }

    /** Min transfers first (nulls last), then cheapest price (nulls last) — the TR-f2 ranking rule. */
    private static final Comparator<FlightOffer> FLIGHT_ORDER =
            Comparator.comparing(FlightOffer::transfers, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(FlightOffer::price, Comparator.nullsLast(Comparator.naturalOrder()));

    private ObjectNode flightNode(FlightOffer o, BigDecimal budgetRef) {
        ObjectNode n = json.createObjectNode();
        if (o.price() != null) n.put("price", o.price());
        if (o.currency() != null) n.put("currency", o.currency());
        if (o.transfers() != null) n.put("transfers", o.transfers());
        if (o.airline() != null) n.put("airline", o.airline());
        if (o.departDate() != null) n.put("departDate", o.departDate());
        if (o.returnDate() != null) n.put("returnDate", o.returnDate());
        if (o.deepLink() != null) n.put("deepLink", o.deepLink());
        n.put("overBudget", overBudget(o.price(), budgetRef));
        return n;
    }

    private ObjectNode hotelNode(HotelOffer o, BigDecimal budgetRef) {
        ObjectNode n = json.createObjectNode();
        if (o.name() != null) n.put("name", o.name());
        if (o.price() != null) n.put("price", o.price());
        if (o.currency() != null) n.put("currency", o.currency());
        if (o.stars() != null) n.put("stars", o.stars());
        if (o.deepLink() != null) n.put("deepLink", o.deepLink());
        n.put("overBudget", overBudget(o.price(), budgetRef));
        return n;
    }

    private static boolean overBudget(Double price, BigDecimal budgetRef) {
        return price != null && budgetRef != null && BigDecimal.valueOf(price).compareTo(budgetRef) > 0;
    }

    /** The numeric ceiling used to flag over-budget options — the owner's stated budget hint, if any. */
    private static BigDecimal budgetRef(TravelProfileDto profile) {
        return profile.budgetAmount();
    }

    private ObjectNode unconfiguredOptions() {
        ObjectNode node = json.createObjectNode();
        node.put("unconfigured", true);
        return node;
    }

    /** solo → 1, couple/family → 2 adults (the search's party size). */
    private static int partySize(TravelProfileDto profile) {
        String c = profile.companions();
        return ("couple".equals(c) || "family".equals(c)) ? 2 : 1;
    }

    /** A future {@code yyyy-MM} for the flight search: this year if the month is still ahead, else next. */
    private static String monthCode(int month) {
        YearMonth now = YearMonth.now();
        int year = (month >= now.getMonthValue()) ? now.getYear() : now.getYear() + 1;
        return String.format("%04d-%02d", year, month);
    }

    /** A concrete {@code [checkIn, checkOut]} for the hotel search: mid-month, a default stay length. */
    private static String[] stayDates(int month) {
        YearMonth now = YearMonth.now();
        int year = (month >= now.getMonthValue()) ? now.getYear() : now.getYear() + 1;
        LocalDate checkIn = LocalDate.of(year, month, 12);
        LocalDate checkOut = checkIn.plusDays(DEFAULT_STAY_NIGHTS);
        return new String[]{checkIn.toString(), checkOut.toString()};
    }

    /** Append the "live search not set up" note when the capability degraded (unconfigured). */
    private static String withLiveNote(String text, JsonNode gathered) {
        if (gathered != null && gathered.path("liveOptions").path("unconfigured").asBoolean(false)) {
            return text + "\n\n" + LIVE_UNCONFIGURED_NOTE;
        }
        return text;
    }

    /** Invoke a specialist's read-only {@code brief} via the hub; any soft-failure → omitted from the context. */
    private Mono<JsonNode> brief(String agent, NormalizedMessage msg, String question) {
        ObjectNode args = json.createObjectNode();
        args.put("question", question);
        AgentActionRequest req = new AgentActionRequest(
                agent, BRIEF_ACTION, msg.householdId(), msg.userId(), "travel", args);
        return hub.invoke(req, BRIEF_TIMEOUT)
                .flatMap(result -> {
                    String answer = answerOf(result);
                    return answer == null ? Mono.<JsonNode>empty() : Mono.just(StringNode.valueOf(answer));
                })
                .onErrorResume(e -> {
                    log.warn("{} brief failed: {}", agent, e.toString());
                    return Mono.empty();
                });
    }

    private static String answerOf(AgentActionResult result) {
        if (result == null || !result.ok() || result.result() == null) {
            return null;
        }
        JsonNode answer = result.result().get("answer");
        if (answer == null || answer.isNull()) {
            return null;
        }
        String text = answer.asString("").strip();
        return text.isEmpty() ? null : text;
    }

    private String budgetQuestion(NormalizedMessage msg, TravelProfileDto profile) {
        StringBuilder q = new StringBuilder("Планируется поездка/отпуск: \"")
                .append(msg.text() == null ? "" : msg.text())
                .append("\". Какой у семьи бюджет и запас на такую поездку с учётом недавних трат?");
        if (profile.budgetAmount() != null) {
            q.append(" Ориентир пользователя: ~").append(profile.budgetAmount());
            if (profile.budgetCurrency() != null) {
                q.append(' ').append(profile.budgetCurrency());
            }
            q.append('.');
        }
        return q.toString();
    }

    private String datesQuestion(NormalizedMessage msg, Scope scope) {
        StringBuilder q = new StringBuilder("Планируется поездка: \"")
                .append(msg.text() == null ? "" : msg.text())
                .append("\". Какие свободные даты и какие занятые/конфликтующие события есть");
        if (scope.month() != null) {
            q.append(" в месяце ").append(scope.month());
        }
        q.append("?");
        return q.toString();
    }

    /** A search query for destination ideas / season reviews — a named place, else the rest-style wish. */
    private String researchQuery(NormalizedMessage msg, TravelProfileDto profile, Scope scope) {
        if (scope.destination() != null && !scope.destination().isBlank()) {
            return scope.destination() + " когда ехать сезон отзывы";
        }
        String rest = restTypesJoined(profile);
        String base = rest.isBlank() ? (msg.text() == null ? "куда поехать в отпуск" : msg.text())
                : rest + " отдых куда поехать";
        return scope.month() == null ? base : base + " в месяце " + scope.month();
    }

    private ObjectNode profileNode(TravelProfileDto p) {
        ObjectNode node = json.createObjectNode();
        if (p.homeBaseLabel() != null) node.put("homeBase", p.homeBaseLabel());
        if (p.restTypes() != null && !p.restTypes().isNull()) node.set("restTypes", p.restTypes());
        if (p.companions() != null) node.put("companions", p.companions());
        if (p.childAges() != null && !p.childAges().isNull()) node.set("childAges", p.childAges());
        if (p.budgetAmount() != null) {
            ObjectNode hint = node.putObject("budgetHint");
            hint.put("amount", p.budgetAmount());
            if (p.budgetCurrency() != null) hint.put("currency", p.budgetCurrency());
        }
        return node;
    }

    private String restTypesJoined(TravelProfileDto p) {
        if (p.restTypes() == null || !p.restTypes().isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : p.restTypes()) {
            if (n != null && n.isString()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(n.asString());
            }
        }
        return sb.toString();
    }

    private Scope parseScope(String content) {
        if (content == null) {
            return Scope.EMPTY;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Scope.EMPTY;
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            if (!node.isObject()) {
                return Scope.EMPTY;
            }
            String dest = node.hasNonNull("destination") ? node.get("destination").asString().trim() : null;
            if (dest != null && dest.isEmpty()) {
                dest = null;
            }
            Integer month = null;
            if (node.hasNonNull("month") && node.get("month").isNumber()) {
                int m = node.get("month").asInt();
                if (m >= 1 && m <= 12) {
                    month = m;
                }
            }
            boolean live = node.hasNonNull("live") && node.get("live").asBoolean(false);
            return new Scope(dest, month, live);
        } catch (Exception e) {
            return Scope.EMPTY;
        }
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "trip-composer SKILL.md not loaded — check skills-classpath"));
    }

    private static TravelProfileDto emptyProfile(NormalizedMessage msg) {
        return new TravelProfileDto(null, msg.householdId(), msg.userId(), null, null, null,
                null, null, null, null, null, null, null);
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    private static final GeoLocation NO_GEO = new GeoLocation(null, null, null, null, null);
    /** A "no home base to resolve" placeholder so the flight search is simply skipped (hotels still run). */
    private static final PlaceResult NO_PLACE = new PlaceResult(null, null, null, null, false);

    /**
     * The FAST scope extract's result: a named destination + a stated travel month (either may be absent)
     * and whether the owner asked for concrete live tickets/hotels ({@code live}, TR-f2).
     */
    private record Scope(String destination, Integer month, boolean live) {
        static final Scope EMPTY = new Scope(null, null, false);
    }
}
