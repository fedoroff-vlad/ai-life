package dev.fedorov.ailife.mcp.travelsearch.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.travelsearch.FlightOffer;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelOffer;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchInput;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.PlaceQueryInput;
import dev.fedorov.ailife.contracts.travelsearch.PlaceResult;
import dev.fedorov.ailife.mcp.travelsearch.config.McpTravelSearchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Default {@link TravelSearchSource}: live prices over <b>Travelpayouts</b> — flights from the Aviasales
 * {@code /aviasales/v3/prices_for_dates} data API, hotels from the Hotellook {@code /api/v2/cache.json}
 * data API, and place resolution from the Travelpayouts autocomplete {@code /places2}. Selected by
 * {@code travelsearch.source=travelpayouts} (the default).
 *
 * <p><b>Owner-key-gated (ADR-0003).</b> When no affiliate token is wired ({@code travelsearch.token}
 * blank) every method short-circuits to an {@code unconfigured} result <b>without</b> an HTTP call — so
 * the capability is inert and CI/tests stay green with no key, and callers degrade to the planner MVP. A
 * configured call whose upstream is unreachable <b>soft-fails</b> to an empty (configured) result, never a
 * 500. <b>Read-only</b> — no book/pay; each offer carries only a provider {@code deepLink} (marked with
 * the affiliate marker) the owner opens to buy. Modeled from the public Travelpayouts API shapes; the live
 * field mapping is validated once the owner wires a real key (the no-key/degrade/soft-fail paths are
 * fully covered without one).
 */
@Component
@ConditionalOnProperty(name = "travelsearch.source", havingValue = "travelpayouts", matchIfMissing = true)
public class TravelpayoutsTravelSearchSource implements TravelSearchSource {

    private static final Logger log = LoggerFactory.getLogger(TravelpayoutsTravelSearchSource.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    private final WebClient aviasales;
    private final WebClient hotellook;
    private final WebClient autocomplete;
    private final McpTravelSearchProperties props;
    private final ObjectMapper json;

    public TravelpayoutsTravelSearchSource(@Qualifier("aviasalesWebClient") WebClient aviasales,
                                           @Qualifier("hotellookWebClient") WebClient hotellook,
                                           @Qualifier("autocompleteWebClient") WebClient autocomplete,
                                           McpTravelSearchProperties props,
                                           ObjectMapper json) {
        this.aviasales = aviasales;
        this.hotellook = hotellook;
        this.autocomplete = autocomplete;
        this.props = props;
        this.json = json;
    }

    @Override
    public Mono<PlaceResult> resolvePlace(PlaceQueryInput input) {
        String query = input == null ? null : input.query();
        if (!props.isConfigured()) {
            return Mono.just(PlaceResult.unconfigured(query));
        }
        if (query == null || query.isBlank()) {
            return Mono.just(new PlaceResult(query, null, null, null, false));
        }
        return autocomplete.get()
                .uri(uri -> uri.path("/places2")
                        .queryParam("term", query)
                        .queryParam("locale", "en")
                        .queryParam("types[]", "city")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .map(body -> parsePlace(query, body))
                .onErrorResume(e -> {
                    log.warn("travelpayouts resolve_place failed for '{}': {}", query, e.toString());
                    return Mono.just(new PlaceResult(query, null, null, null, false));
                });
    }

    @Override
    public Mono<FlightSearchResult> searchFlights(FlightSearchInput input) {
        if (!props.isConfigured()) {
            return Mono.just(FlightSearchResult.degraded());
        }
        boolean oneWay = input.returnDate() == null || input.returnDate().isBlank();
        return aviasales.get()
                .uri(uri -> {
                    uri.path("/aviasales/v3/prices_for_dates")
                            .queryParam("origin", nz(input.origin()))
                            .queryParam("destination", nz(input.destination()))
                            .queryParam("departure_at", nz(input.departDate()))
                            .queryParam("currency", props.getCurrency())
                            .queryParam("one_way", oneWay)
                            .queryParam("sorting", "price")
                            .queryParam("limit", props.getMaxResults())
                            .queryParam("token", props.getToken());
                    if (!oneWay) {
                        uri.queryParam("return_at", input.returnDate());
                    }
                    return uri.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .map(this::parseFlights)
                .onErrorResume(e -> {
                    log.warn("travelpayouts search_flights failed: {}", e.toString());
                    return Mono.just(FlightSearchResult.of(List.of()));
                });
    }

    @Override
    public Mono<HotelSearchResult> searchHotels(HotelSearchInput input) {
        if (!props.isConfigured()) {
            return Mono.just(HotelSearchResult.degraded());
        }
        return hotellook.get()
                .uri(uri -> uri.path("/api/v2/cache.json")
                        .queryParam("location", nz(input.location()))
                        .queryParam("checkIn", nz(input.checkIn()))
                        .queryParam("checkOut", nz(input.checkOut()))
                        .queryParam("currency", props.getCurrency())
                        .queryParam("limit", props.getMaxResults())
                        .queryParam("token", props.getToken())
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .map(this::parseHotels)
                .onErrorResume(e -> {
                    log.warn("travelpayouts search_hotels failed: {}", e.toString());
                    return Mono.just(HotelSearchResult.of(List.of()));
                });
    }

    /** Autocomplete {@code /places2} → the first city's IATA {@code code} + {@code name}. */
    private PlaceResult parsePlace(String query, String body) {
        JsonNode root = readTree(body);
        if (root != null && root.isArray()) {
            for (JsonNode n : root) {
                String code = text(n, "code");
                String name = text(n, "name");
                if (code != null) {
                    // hotelLocationId = the resolved name (Hotellook cache.json accepts a location name).
                    return new PlaceResult(query, name, code, name, false);
                }
            }
        }
        return new PlaceResult(query, null, null, null, false);
    }

    /** Aviasales {@code data[]} → offers, cheapest first, each with an affiliate buy {@code deepLink}. */
    private FlightSearchResult parseFlights(String body) {
        JsonNode root = readTree(body);
        List<FlightOffer> offers = new ArrayList<>();
        JsonNode data = root == null ? null : root.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                Double price = number(n, "price");
                if (price == null) {
                    continue;
                }
                Integer transfers = intOrNull(n, "transfers");
                String airline = text(n, "airline");
                String depart = text(n, "departure_at");
                String ret = text(n, "return_at");
                String link = text(n, "link");
                offers.add(new FlightOffer(price, currency(), transfers, airline, depart, ret,
                        flightDeepLink(link)));
            }
        }
        offers.sort(Comparator.comparingDouble(FlightOffer::price));
        return FlightSearchResult.of(offers);
    }

    /** Hotellook {@code cache.json} array → offers, each with an affiliate buy {@code deepLink}. */
    private HotelSearchResult parseHotels(String body) {
        JsonNode root = readTree(body);
        List<HotelOffer> offers = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode n : root) {
                Double price = number(n, "priceFrom");
                String name = text(n, "hotelName");
                if (name == null && price == null) {
                    continue;
                }
                Double stars = number(n, "stars");
                String hotelId = text(n, "hotelId");
                offers.add(new HotelOffer(name, price, currency(), stars, hotelDeepLink(hotelId)));
            }
        }
        return HotelSearchResult.of(offers);
    }

    private String flightDeepLink(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        String base = props.getAviasalesSiteUrl() + link;
        return appendMarker(base);
    }

    private String hotelDeepLink(String hotelId) {
        String base = props.getHotellookSiteUrl();
        if (hotelId != null && !hotelId.isBlank()) {
            base = base + "/hotels/" + hotelId;
        }
        return appendMarker(base);
    }

    private String appendMarker(String url) {
        if (props.getMarker() == null || props.getMarker().isBlank()) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "marker=" + props.getMarker();
    }

    private String currency() {
        String c = props.getCurrency();
        return c == null ? null : c.toUpperCase(Locale.ROOT);
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (Exception e) {
            log.warn("travelpayouts response parse failed: {}", e.toString());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || !v.isNumber()) ? null : v.asDouble();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || !v.isNumber()) ? null : v.asInt();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
