package dev.fedorov.ailife.agents.travel.profile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.travel.http.GeocodeClient;
import dev.fedorov.ailife.agents.travel.http.TravelProfileClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.travel.SetTravelProfileInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Turns a typed message stating travel preferences into a stored {@code travel_profile} row. One
 * llm-gateway {@code DEFAULT} turn with the {@code travel-profiler} SKILL as the system prompt extracts
 * the preferences JSON; if a home-base city was stated, one {@code mcp-weather} geocode resolves it to
 * coordinates; then a write via {@code mcp-travel}'s {@code POST /internal/travel-profile}.
 *
 * <p>Scope: the SKILL emits {@code "self"} (the speaker's own prefs → {@code ownerId = userId}) or
 * {@code "household"} (shared family prefs → {@code ownerId = null}, the household-default). Geocoding
 * soft-fails — a hiccup saves the profile without coordinates. <b>Vocabulary enforcement lives here</b>
 * (the write path): {@code restTypes} is filtered to the fixed vocabulary and {@code companions} is
 * dropped unless valid, so an out-of-vocabulary value never reaches the store. Mirrors briefing-agent's
 * {@code BriefingProfiler} plus the geocode step.
 */
@Component
public class TravelProfiler {

    private static final Logger log = LoggerFactory.getLogger(TravelProfiler.class);
    private static final String SKILL_NAME = "travel-profiler";
    private static final GeoLocation NO_GEO = new GeoLocation(null, null, null, null, null);

    /** The fixed rest-type vocabulary — anything outside it is dropped, never stored. */
    private static final Set<String> REST_TYPES =
            Set.of("beach", "active", "family", "couple", "city", "ski", "wellness");
    private static final Set<String> COMPANIONS = Set.of("solo", "couple", "family");

    private final TravelProfileClient profiles;
    private final GeocodeClient geocode;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public TravelProfiler(TravelProfileClient profiles, GeocodeClient geocode, LlmClient llm,
                          SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.profiles = profiles;
        this.geocode = geocode;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> setProfile(NormalizedMessage msg) {
        // temperature=0: preference extraction must be deterministic/faithful, not creative.
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(msg.text())), 0.0);
        return llm.chat(request)
                .flatMap(r -> build(msg, r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("travel-profiler failed: {}", e.toString());
                    return Mono.just(reply("Не удалось обновить настройки путешествий. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> build(NormalizedMessage msg, String llmContent, String model) {
        JsonNode draft = parseDraft(llmContent);
        if (draft == null) {
            return Mono.just(reply(
                    "Не понял настройки путешествий. Напишите, например: «мы семья с ребёнком 4 года, любим пляж, летаем из Москвы, бюджет тысяч 200».",
                    model));
        }
        String city = text(draft, "homeBase");
        Mono<GeoLocation> location = (city == null || city.isBlank())
                ? Mono.just(NO_GEO)
                : geocode.geocode(city, "ru").defaultIfEmpty(NO_GEO);
        return location.flatMap(loc -> write(msg, draft, city, loc, model));
    }

    private Mono<IntentResponse> write(NormalizedMessage msg, JsonNode draft, String city,
                                       GeoLocation loc, String model) {
        boolean household = "household".equalsIgnoreCase(text(draft, "scope"));
        SetTravelProfileInput input = new SetTravelProfileInput(
                msg.householdId(),
                household ? null : msg.userId(),   // self → the sender; household → the default
                city,                              // stored label = the stated city (user's language)
                loc.latitude(),
                loc.longitude(),
                filterVocab(array(draft, "restTypes"), REST_TYPES),
                whitelisted(text(draft, "companions"), COMPANIONS),
                intArray(draft, "childAges"),
                decimal(draft, "budgetAmount"),
                text(draft, "budgetCurrency"),
                text(draft, "notes"));
        return profiles.set(input)
                .map(saved -> reply(successText(household, saved.homeBaseLabel(), loc), model))
                .onErrorResume(e -> {
                    log.warn("set_travel_profile write failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить настройки путешествий. Попробуйте позже.", null));
                });
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "travel-profiler SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private JsonNode parseDraft(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            if (!node.isObject() || node.hasNonNull("error")) {
                return null;
            }
            return node;
        } catch (Exception e) {
            return null;
        }
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

    private static String successText(boolean household, String label, GeoLocation loc) {
        StringBuilder sb = new StringBuilder(household
                ? "Обновил общие настройки путешествий" : "Обновил ваши настройки путешествий");
        if (label != null && !label.isBlank()) {
            sb.append(" (город вылета: ").append(label);
            if (loc.latitude() == null) {
                sb.append(" — не удалось определить координаты, уточните название");
            }
            sb.append(")");
        }
        sb.append(". Поправьте, если что-то не так.");
        return sb.toString();
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
