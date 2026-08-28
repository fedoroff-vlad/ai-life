package dev.fedorov.ailife.agents.briefing.profile;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.agentruntime.profile.PersonalizationProfiler;
import dev.fedorov.ailife.agentruntime.profile.ProfileSpec;
import dev.fedorov.ailife.agents.briefing.http.BriefingProfileClient;
import dev.fedorov.ailife.agentruntime.http.GeocodeClient;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Turns a typed message configuring a morning briefing into a stored {@code briefing_profile} row. Since
 * ADR-0005 (slice 3) this is the reference retrofit onto the shared {@link PersonalizationProfiler}
 * template: the LLM extract via the {@code briefing-profiler} SKILL, the lenient JSON parse, the
 * {@code self}/{@code household} scope resolution, and the write soft-fail all live in the template. This
 * class is the briefing {@link ProfileSpec} — only what stays domain-specific: the field mapping into
 * {@link SetBriefingProfileInput}, the {@code mcp-weather} geocode post-step for a stated city, and the
 * Russian reply wording. Geocoding soft-fails — a hiccup saves the profile without coordinates.
 */
@Component
public class BriefingProfiler implements ProfileSpec<SetBriefingProfileInput, BriefingProfileDto> {

    private static final String SKILL_NAME = "briefing-profiler";
    private static final GeoLocation NO_GEO = new GeoLocation(null, null, null, null, null);

    private final PersonalizationProfiler template;
    private final BriefingProfileClient profiles;
    private final GeocodeClient geocode;

    public BriefingProfiler(PersonalizationProfiler template, BriefingProfileClient profiles,
                            GeocodeClient geocode) {
        this.template = template;
        this.profiles = profiles;
        this.geocode = geocode;
    }

    /** The briefing-profiler flow (BR-c): extract + geocode + upsert, via the shared template. */
    public Mono<IntentResponse> setProfile(NormalizedMessage msg) {
        return template.setProfile(this, msg);
    }

    @Override
    public String skillName() {
        return SKILL_NAME;
    }

    @Override
    public Mono<SetBriefingProfileInput> build(JsonNode draft, UUID ownerId, NormalizedMessage msg) {
        String city = text(draft, "location");
        Mono<GeoLocation> location = (city == null || city.isBlank())
                ? Mono.just(NO_GEO)
                : geocode.geocode(city, "ru").defaultIfEmpty(NO_GEO);
        return location.map(loc -> new SetBriefingProfileInput(
                msg.householdId(),
                ownerId,                           // scoped by the template: self → sender, household → null
                city,                              // stored label = the stated city (user's language)
                loc.latitude(),
                loc.longitude(),
                loc.timezone(),
                array(draft, "interests"),
                array(draft, "sections"),
                text(draft, "scheduleTime"),
                bool(draft, "scheduleEnabled"),
                text(draft, "notes")));
    }

    @Override
    public Mono<BriefingProfileDto> write(SetBriefingProfileInput input) {
        return profiles.set(input);
    }

    @Override
    public String success(boolean household, BriefingProfileDto saved) {
        StringBuilder sb = new StringBuilder(household
                ? "Обновил общие настройки брифинга" : "Обновил ваши настройки брифинга");
        String label = saved.locationLabel();
        if (label != null && !label.isBlank()) {
            sb.append(" (город: ").append(label);
            if (saved.latitude() == null) {
                sb.append(" — не удалось определить координаты, уточните название");
            }
            sb.append(")");
        }
        sb.append(". Поправьте, если что-то не так.");
        return sb.toString();
    }

    @Override
    public String unparseable() {
        return "Не понял настройки брифинга. Напишите, например: "
                + "«каждое утро в 8:00 показывай погоду в Москве и новости про ИИ».";
    }

    @Override
    public String failure() {
        return "Не удалось обновить настройки брифинга. Попробуйте позже.";
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static JsonNode array(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isArray() ? node.get(field) : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).isBoolean() ? node.get(field).asBoolean() : null;
    }
}
