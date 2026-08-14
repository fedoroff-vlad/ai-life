package dev.fedorov.ailife.agents.travel.flow;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agents.travel.flow.PackingListComposer.Category;
import dev.fedorov.ailife.agents.travel.flow.PackingListComposer.ClimateBand;
import dev.fedorov.ailife.agents.travel.flow.PackingListComposer.PackingContext;
import dev.fedorov.ailife.agents.travel.flow.PackingListComposer.PackingList;
import dev.fedorov.ailife.agents.travel.http.ClimateClient;
import dev.fedorov.ailife.agentruntime.http.GeocodeClient;
import dev.fedorov.ailife.agents.travel.http.TravelProfileClient;
import dev.fedorov.ailife.agents.travel.http.TripWalletClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.contracts.travel.TripDto;
import dev.fedorov.ailife.contracts.weather.ClimateNormals;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.MonthlyNormal;
import dev.fedorov.ailife.docrender.Doc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The packing-list flow (PK-a, #438): a cue-routed <b>"что взять с собой"</b> → a deterministic categorized
 * packing list tailored to the active trip's <b>season</b> and the person's <b>rest types + companions</b>,
 * delivered as a short reply + an HTML board (the TR-e {@link DeliverablePublisher} seam).
 *
 * <p>Context is <b>gathered, not asked</b>: the household's active trip (EX-a {@code getActiveTrip}) gives
 * the destination + month → the season band via the existing geocode→climate chain (mirrors
 * {@link TripComposer}); the {@code travel_profile} (self → household-default → empty, mirrors TR-d's
 * resolve) gives rest types + companions + child ages. Every source <b>soft-fails</b>: no active trip → a
 * profile-only generic list + a nudge to create one; no destination/date or a climate hiccup → the
 * climate-driven items are dropped and the list notes the weather is unconfirmed.
 *
 * <p>The list itself is built by {@link PackingListComposer} — <b>pure Java, never the LLM</b>.
 */
@Component
public class PackingFlow {

    private static final Logger log = LoggerFactory.getLogger(PackingFlow.class);
    /** Monthly precipitation (mm) at/above which the season counts as wet (rain gear). */
    private static final double WET_MM = 100.0;
    private static final String NUDGE =
            "Создайте поездку («создай поездку в …»), чтобы я учёл направление и сезон в списке.";
    private static final String WEATHER_UNKNOWN =
            "Погоду для направления уточнить не удалось — список без привязки к сезону.";

    private final TripWalletClient wallet;
    private final TravelProfileClient profiles;
    private final GeocodeClient geocode;
    private final ClimateClient climate;
    private final DeliverablePublisher publisher;
    private final AgentManifest manifest;

    public PackingFlow(TripWalletClient wallet, TravelProfileClient profiles, GeocodeClient geocode,
                       ClimateClient climate, DeliverablePublisher publisher, AgentManifest manifest) {
        this.wallet = wallet;
        this.profiles = profiles;
        this.geocode = geocode;
        this.climate = climate;
        this.publisher = publisher;
        this.manifest = manifest;
    }

    public Mono<IntentResponse> handle(NormalizedMessage msg) {
        return resolveProfile(msg)
                .flatMap(profile -> activeTrip(msg)
                        .flatMap(tripOpt -> deriveClimate(tripOpt)
                                .flatMap(info -> build(msg, profile, tripOpt, info))))
                .onErrorResume(e -> {
                    log.warn("packing flow failed: {}", e.toString());
                    return Mono.just(reply("Не удалось собрать список вещей. Попробуйте позже."));
                });
    }

    /** self → household-default → an empty default; a broken mcp-travel soft-fails to it. */
    private Mono<TravelProfileDto> resolveProfile(NormalizedMessage msg) {
        return profiles.get(msg.householdId(), msg.userId())
                .switchIfEmpty(profiles.get(msg.householdId(), null))
                .switchIfEmpty(Mono.just(emptyProfile(msg)))
                .onErrorResume(e -> {
                    log.warn("travel profile resolve failed, using defaults: {}", e.toString());
                    return Mono.just(emptyProfile(msg));
                });
    }

    /** The household's active trip as an Optional; a 204/hiccup → empty (a profile-only list follows). */
    private Mono<Optional<TripDto>> activeTrip(NormalizedMessage msg) {
        return wallet.getActiveTrip(msg.householdId())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    log.warn("active trip resolve failed, packing without a trip: {}", e.toString());
                    return Mono.just(Optional.empty());
                });
    }

    /** Destination + trip month → season band via geocode→climate; anything missing → UNKNOWN. */
    private Mono<ClimateInfo> deriveClimate(Optional<TripDto> tripOpt) {
        if (tripOpt.isEmpty()) {
            return Mono.just(ClimateInfo.UNKNOWN);
        }
        TripDto trip = tripOpt.get();
        if (trip.destination() == null || trip.destination().isBlank() || trip.startDate() == null) {
            return Mono.just(ClimateInfo.UNKNOWN);
        }
        int month = trip.startDate().getMonthValue();
        return geocode.geocode(trip.destination(), null)
                .flatMap(geo -> {
                    if (geo.latitude() == null || geo.longitude() == null) {
                        return Mono.just(ClimateInfo.UNKNOWN);
                    }
                    return climate.climate(geo.latitude(), geo.longitude(), month)
                            .map(normals -> toInfo(normals, month))
                            .defaultIfEmpty(ClimateInfo.UNKNOWN);
                })
                .defaultIfEmpty(ClimateInfo.UNKNOWN);
    }

    /** Pick the month's normal (single-entry when asked for a month; else match), map °C → band + wetness. */
    private static ClimateInfo toInfo(ClimateNormals normals, int month) {
        if (normals == null || normals.months() == null || normals.months().isEmpty()) {
            return ClimateInfo.UNKNOWN;
        }
        MonthlyNormal picked = normals.months().stream()
                .filter(m -> m.month() == month)
                .findFirst()
                .orElse(normals.months().get(0));
        ClimateBand band = ClimateBand.ofAvgTempC(picked.avgTempC());
        boolean wet = picked.precipMm() != null && picked.precipMm() >= WET_MM;
        return new ClimateInfo(band, wet);
    }

    private Mono<IntentResponse> build(NormalizedMessage msg, TravelProfileDto profile,
                                       Optional<TripDto> tripOpt, ClimateInfo info) {
        PackingContext ctx = new PackingContext(
                restTypes(profile), profile.companions(), childAges(profile), info.band(), info.wet());
        PackingList list = PackingListComposer.compose(ctx);
        String text = renderText(tripOpt, list);
        return publishBoard(msg, tripOpt, list)
                .map(link -> reply(withLink(text, link)))
                .defaultIfEmpty(reply(text))
                .onErrorResume(e -> {
                    log.warn("packing board store failed: {}", e.toString());
                    return Mono.just(reply(text));
                });
    }

    // --- rendering ---

    private String renderText(Optional<TripDto> tripOpt, PackingList list) {
        StringBuilder sb = new StringBuilder();
        if (tripOpt.isPresent()) {
            sb.append("Список вещей для поездки «").append(tripLabel(tripOpt.get())).append("»:\n");
        } else {
            sb.append("Примерный список вещей в дорогу:\n");
        }
        for (Category c : list.categories()) {
            sb.append("\n").append(c.name()).append(":\n");
            for (String item : c.items()) {
                sb.append("• ").append(item).append('\n');
            }
        }
        if (tripOpt.isEmpty()) {
            sb.append('\n').append(NUDGE);
        } else if (!list.weatherKnown()) {
            sb.append('\n').append(WEATHER_UNKNOWN);
        }
        return sb.toString().stripTrailing();
    }

    private Mono<String> publishBoard(NormalizedMessage msg, Optional<TripDto> tripOpt, PackingList list) {
        String kicker = tripOpt.map(t -> "Поездка · " + tripLabel(t)).orElse("В дорогу");
        Doc.Builder b = Doc.builder("Список вещей")
                .kicker(kicker)
                .subtitle(list.weatherKnown() ? "С учётом сезона и типа отдыха" : "Базовый список");
        for (Category c : list.categories()) {
            b.section(c.name(), new ArrayList<>(c.items()));
        }
        if (tripOpt.isEmpty()) {
            b.section("Совет", List.of(NUDGE));
        } else if (!list.weatherKnown()) {
            b.section("Погода", List.of(WEATHER_UNKNOWN));
        }
        return publisher.publish(msg.householdId(), msg.userId(), b.build());
    }

    private static String tripLabel(TripDto trip) {
        if (trip.destination() != null && !trip.destination().isBlank()) {
            return trip.destination();
        }
        return trip.title() == null || trip.title().isBlank() ? "Поездка" : trip.title();
    }

    // --- profile extraction ---

    /** The profile's {@code restTypes} JSON array → a list of strings (empty when absent/malformed). */
    private static List<String> restTypes(TravelProfileDto p) {
        List<String> out = new ArrayList<>();
        JsonNode node = p.restTypes();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode n : node) {
            if (n != null && n.isString()) {
                out.add(n.asString());
            }
        }
        return out;
    }

    /** The profile's {@code childAges} JSON array → a list of ints (empty when absent/malformed). */
    private static List<Integer> childAges(TravelProfileDto p) {
        List<Integer> out = new ArrayList<>();
        JsonNode node = p.childAges();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode n : node) {
            if (n != null && n.isNumber()) {
                out.add(n.asInt());
            }
        }
        return out;
    }

    // --- helpers ---

    private static String withLink(String text, String link) {
        return (link == null || link.isBlank()) ? text : text + "\n\nОткрыть список: " + link;
    }

    private static TravelProfileDto emptyProfile(NormalizedMessage msg) {
        return new TravelProfileDto(null, msg.householdId(), msg.userId(), null, null, null,
                null, null, null, null, null, null, null);
    }

    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }

    /** Derived season for the trip month: the clothing band + whether rain gear is warranted. */
    private record ClimateInfo(ClimateBand band, boolean wet) {
        static final ClimateInfo UNKNOWN = new ClimateInfo(ClimateBand.UNKNOWN, false);
    }
}
