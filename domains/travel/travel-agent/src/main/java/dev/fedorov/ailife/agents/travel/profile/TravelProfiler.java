package dev.fedorov.ailife.agents.travel.profile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import dev.fedorov.ailife.agentruntime.profile.PersonalizationProfiler;
import dev.fedorov.ailife.agentruntime.profile.ProfileSpec;
import dev.fedorov.ailife.agentruntime.http.GeocodeClient;
import dev.fedorov.ailife.agents.travel.http.TravelProfileClient;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.travel.SetTravelProfileInput;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a typed message stating travel preferences into a stored {@code travel_profile} row. Since
 * ADR-0005 (slice 6) this is a thin {@link ProfileSpec} on the shared {@link PersonalizationProfiler}
 * template: the LLM extract via the {@code travel-profiler} SKILL, the parse, the {@code self}/{@code
 * household} scope resolution, and the write soft-fail all live in the template. Only the domain-specific
 * field mapping into {@link SetTravelProfileInput}, the {@code mcp-weather} geocode post-step for a stated
 * home-base city, the reply wording, and — importantly — the <b>vocabulary enforcement</b> (rest-types /
 * companions filtered to a fixed vocabulary so an out-of-vocabulary value never reaches the store) stay
 * here. Geocoding soft-fails — a hiccup saves the profile without coordinates.
 */
@Component
public class TravelProfiler implements ProfileSpec<SetTravelProfileInput, TravelProfileDto> {

    private static final String SKILL_NAME = "travel-profiler";
    private static final GeoLocation NO_GEO = new GeoLocation(null, null, null, null, null);

    /** The fixed rest-type vocabulary — anything outside it is dropped, never stored. */
    private static final Set<String> REST_TYPES =
            Set.of("beach", "active", "family", "couple", "city", "ski", "wellness");
    private static final Set<String> COMPANIONS = Set.of("solo", "couple", "family");

    private final PersonalizationProfiler template;
    private final TravelProfileClient profiles;
    private final GeocodeClient geocode;
    private final ObjectMapper json;

    public TravelProfiler(PersonalizationProfiler template, TravelProfileClient profiles,
                          GeocodeClient geocode, ObjectMapper json) {
        this.template = template;
        this.profiles = profiles;
        this.geocode = geocode;
        this.json = json;
    }

    public Mono<IntentResponse> setProfile(NormalizedMessage msg) {
        return template.setProfile(this, msg);
    }

    @Override
    public String skillName() {
        return SKILL_NAME;
    }

    @Override
    public Mono<SetTravelProfileInput> build(JsonNode draft, UUID ownerId, NormalizedMessage msg) {
        String city = text(draft, "homeBase");
        Mono<GeoLocation> location = (city == null || city.isBlank())
                ? Mono.just(NO_GEO)
                : geocode.geocode(city, "ru").defaultIfEmpty(NO_GEO);
        return location.map(loc -> new SetTravelProfileInput(
                msg.householdId(),
                ownerId,                           // scoped by the template: self → sender, household → null
                city,                              // stored label = the stated city (user's language)
                loc.latitude(),
                loc.longitude(),
                filterVocab(array(draft, "restTypes"), REST_TYPES),
                whitelisted(text(draft, "companions"), COMPANIONS),
                intArray(draft, "childAges"),
                decimal(draft, "budgetAmount"),
                text(draft, "budgetCurrency"),
                text(draft, "notes")));
    }

    @Override
    public Mono<TravelProfileDto> write(SetTravelProfileInput input) {
        return profiles.set(input);
    }

    @Override
    public String success(boolean household, TravelProfileDto saved) {
        StringBuilder sb = new StringBuilder(household
                ? "Обновил общие настройки путешествий" : "Обновил ваши настройки путешествий");
        String label = saved.homeBaseLabel();
        if (label != null && !label.isBlank()) {
            sb.append(" (город вылета: ").append(label);
            if (saved.homeBaseLatitude() == null) {
                sb.append(" — не удалось определить координаты, уточните название");
            }
            sb.append(")");
        }
        sb.append(". Поправьте, если что-то не так.");
        return sb.toString();
    }

    @Override
    public String unparseable() {
        return "Не понял настройки путешествий. Напишите, например: "
                + "«мы семья с ребёнком 4 года, любим пляж, летаем из Москвы, бюджет тысяч 200».";
    }

    @Override
    public String failure() {
        return "Не удалось обновить настройки путешествий. Попробуйте позже.";
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static JsonNode array(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isArray() ? node.get(field) : null;
    }

    /** Keep only array entries that are in {@code vocab} (lower-cased); null when nothing survives. */
    private JsonNode filterVocab(JsonNode arr, Set<String> vocab) {
        if (arr == null) {
            return null;
        }
        ArrayNode out = json.createArrayNode();
        for (JsonNode el : arr) {
            if (el != null && el.isString()) {
                String v = el.asString().trim().toLowerCase();
                if (vocab.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static String whitelisted(String value, Set<String> vocab) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase();
        return vocab.contains(v) ? v : null;
    }

    /** Keep only integer entries; null when nothing survives. */
    private JsonNode intArray(JsonNode node, String field) {
        JsonNode arr = array(node, field);
        if (arr == null) {
            return null;
        }
        ArrayNode out = json.createArrayNode();
        for (JsonNode el : arr) {
            if (el != null && el.isNumber()) {
                out.add(el.asInt());
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (!node.hasNonNull(field) || !node.get(field).isNumber()) {
            return null;
        }
        return node.get(field).decimalValue();
    }
}
