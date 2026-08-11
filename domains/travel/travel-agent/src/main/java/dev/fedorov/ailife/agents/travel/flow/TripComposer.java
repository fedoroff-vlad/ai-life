package dev.fedorov.ailife.agents.travel.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.travel.http.ClimateClient;
import dev.fedorov.ailife.agents.travel.http.GeocodeClient;
import dev.fedorov.ailife.agents.travel.http.TravelProfileClient;
import dev.fedorov.ailife.agents.travel.http.WebSearchClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * {@code trip-composer} SKILL; this flow makes no outbound booking call). The HTML travel board is TR-e.
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

    /** Cheap FAST pre-step: spot a concrete destination + month so climate can be gathered for it. */
    private static final String SCOPE_SYSTEM = """
            You extract trip parameters from a short vacation request. Return ONLY a JSON object:
            {"destination": <a place the person explicitly named, or null>, "month": <trip month 1-12, or null>}.
            "destination" is a concrete place (city / country / region) ONLY if the person named one — a
            generic wish like "на море" or "somewhere warm" is NOT a destination, use null. "month" is the
            travel month if stated (by name or number), else null. Output only the JSON — no prose, no code fence.""";

    private final Coordinator coordinator;
    private final TravelProfileClient profiles;
    private final GeocodeClient geocode;
    private final ClimateClient climate;
    private final WebSearchClient web;
    private final OrchestratorInvokeClient hub;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public TripComposer(Coordinator coordinator, TravelProfileClient profiles, GeocodeClient geocode,
                        ClimateClient climate, WebSearchClient web, OrchestratorInvokeClient hub,
                        LlmClient llm, SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.coordinator = coordinator;
        this.profiles = profiles;
        this.geocode = geocode;
        this.climate = climate;
        this.web = web;
        this.hub = hub;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
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
            gather.put("climate", climate.climate(geo.latitude(), geo.longitude(), scope.month())
                    .map(json::valueToTree));
        }
        gather.put("research", web.search(researchQuery(msg, profile, scope), MAX_RESEARCH)
                .map(res -> json.valueToTree(res.hits())));

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
                .map(r -> reply(r.text(), r.llmModel()))
                .onErrorResume(e -> {
                    log.warn("trip synthesis failed: {}", e.toString());
                    return Mono.just(reply("Собрал данные, но не смог оформить план поездки. Попробуйте позже.", null));
                });
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
            return new Scope(dest, month);
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

    /** The FAST scope extract's result: a named destination + a stated travel month, either may be absent. */
    private record Scope(String destination, Integer month) {
        static final Scope EMPTY = new Scope(null, null);
    }
}
