package dev.fedorov.ailife.agents.briefing.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.briefing.http.BriefingProfileClient;
import dev.fedorov.ailife.agents.briefing.http.GeocodeClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Turns a typed message configuring a morning briefing into a stored {@code briefing_profile} row. One
 * llm-gateway {@code DEFAULT} turn with the {@code briefing-profiler} SKILL as the system prompt
 * extracts the preferences JSON; if a city was stated, one {@code mcp-weather} geocode resolves it to
 * coordinates + timezone; then a write via {@code mcp-briefing}'s {@code POST /internal/briefing-profile}.
 *
 * <p>Scope: the SKILL emits {@code "self"} (the speaker's own prefs → {@code ownerId = userId}) or
 * {@code "household"} (a shared briefing → {@code ownerId = null}, the household-default). Geocoding
 * soft-fails — a hiccup saves the profile without coordinates rather than failing the whole update.
 * Mirrors creator-agent's {@code CreatorProfiler} plus the geocode step.
 */
@Component
public class BriefingProfiler {

    private static final Logger log = LoggerFactory.getLogger(BriefingProfiler.class);
    private static final String SKILL_NAME = "briefing-profiler";
    private static final GeoLocation NO_GEO = new GeoLocation(null, null, null, null, null);

    private final BriefingProfileClient profiles;
    private final GeocodeClient geocode;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public BriefingProfiler(BriefingProfileClient profiles, GeocodeClient geocode, LlmClient llm,
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
                    log.warn("briefing-profiler failed: {}", e.toString());
                    return Mono.just(reply("Не удалось обновить настройки брифинга. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> build(NormalizedMessage msg, String llmContent, String model) {
        JsonNode draft = parseDraft(llmContent);
        if (draft == null) {
            return Mono.just(reply(
                    "Не понял настройки брифинга. Напишите, например: «каждое утро в 8:00 показывай погоду в Москве и новости про ИИ».",
                    model));
        }
        String city = text(draft, "location");
        Mono<GeoLocation> location = (city == null || city.isBlank())
                ? Mono.just(NO_GEO)
                : geocode.geocode(city, "ru").defaultIfEmpty(NO_GEO);
        return location.flatMap(loc -> write(msg, draft, city, loc, model));
    }

    private Mono<IntentResponse> write(NormalizedMessage msg, JsonNode draft, String city,
                                       GeoLocation loc, String model) {
        boolean household = "household".equalsIgnoreCase(text(draft, "scope"));
        SetBriefingProfileInput input = new SetBriefingProfileInput(
                msg.householdId(),
                household ? null : msg.userId(),   // self → the sender; household → the default
                city,                              // stored label = the stated city (user's language)
                loc.latitude(),
                loc.longitude(),
                loc.timezone(),
                array(draft, "interests"),
                array(draft, "sections"),
                text(draft, "scheduleTime"),
                bool(draft, "scheduleEnabled"),
                text(draft, "notes"));
        return profiles.set(input)
                .map(saved -> reply(successText(household, saved.locationLabel(), loc), model))
                .onErrorResume(e -> {
                    log.warn("set_briefing_profile write failed: {}", e.toString());
                    return Mono.just(reply("Не смог сохранить настройки брифинга. Попробуйте позже.", null));
                });
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "briefing-profiler SKILL.md not loaded — check skills-classpath"));
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
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static JsonNode array(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isArray() ? node.get(field) : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isBoolean() ? node.get(field).asBoolean() : null;
    }

    private static String successText(boolean household, String label, GeoLocation loc) {
        StringBuilder sb = new StringBuilder(household
                ? "Обновил общие настройки брифинга" : "Обновил ваши настройки брифинга");
        if (label != null && !label.isBlank()) {
            sb.append(" (город: ").append(label);
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
