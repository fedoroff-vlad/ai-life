package dev.fedorov.ailife.agents.coach.flow;

import dev.fedorov.ailife.agentruntime.coordinate.CoordinationResult;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.coach.http.CoachStoreClient;
import dev.fedorov.ailife.agents.coach.http.SubjectNotesClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.coach.AddCoachHypothesisInput;
import dev.fedorov.ailife.contracts.coach.AddCoachObservationInput;
import dev.fedorov.ailife.contracts.coach.CoachProfileDto;
import dev.fedorov.ailife.contracts.coach.CoachSessionDto;
import dev.fedorov.ailife.contracts.coach.StartCoachSessionInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.note.NoteDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The <b>Reflect</b> flow (CO-2): one gather → synthesize turn on the shared {@link Coordinator}, then
 * persist what it surfaced into the durable coaching record.
 *
 * <p><b>Subject = the authenticated sender</b> ({@code msg.userId()}), per coach Decision 0 — every
 * read and write below is scoped to that one person; there is no cross-member access by construction.
 * Gather: the subject's own journal/reflection/goal notes + a semantic memory recall on their request
 * + their recent coach sessions (continuity). The subject's {@code coach_profile} vector rides in the
 * payload and shapes the synthesis (defaults apply when none exists). Synthesis: one DEFAULT-channel
 * call under {@code [AGENT.md, reflect SKILL.md]} returning strict JSON (reply + observations +
 * hypotheses + session summary).
 *
 * <p>Persistence is best-effort <i>after</i> the synthesis: a store outage loses the record, never the
 * conversation. A model that answered in prose instead of JSON still reaches the user (the prose is a
 * valid coaching reply); nothing is persisted from it.
 */
@Component
public class Reflector {

    private static final Logger log = LoggerFactory.getLogger(Reflector.class);
    private static final String SKILL_NAME = "reflect";
    private static final Set<String> METHODS = Set.of("cbt", "act", "mi", "sfbt", "ifs");
    private static final int RECENT_SESSIONS_K = 5;
    private static final int NOTE_BODY_MAX_CHARS = 500;
    private static final String REPLY_FALLBACK =
            "Я подумал над этим, но не смог сформулировать ответ. Расскажите чуть подробнее, что хочется разобрать?";
    private static final String ERROR_REPLY = "Не получилось провести сессию. Попробуйте позже.";

    private final Coordinator coordinator;
    private final MemoryClient memory;
    private final SubjectNotesClient notes;
    private final CoachStoreClient store;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public Reflector(Coordinator coordinator, MemoryClient memory, SubjectNotesClient notes,
                     CoachStoreClient store, SkillRegistry skills, AgentManifest manifest,
                     ObjectMapper json) {
        this.coordinator = coordinator;
        this.memory = memory;
        this.notes = notes;
        this.store = store;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> reflect(NormalizedMessage msg) {
        UUID household = msg.householdId();
        UUID subject = msg.userId();
        String userText = msg.text();
        return store.profile(household, subject)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(profile -> synthesize(household, subject, userText, profile))
                .flatMap(result -> persistAndReply(household, subject, result))
                .onErrorResume(e -> {
                    log.warn("reflect session failed for subject={}: {}", subject, e.toString());
                    return Mono.just(reply(ERROR_REPLY, null));
                });
    }

    private Mono<CoordinationResult> synthesize(UUID household, UUID subject, String userText,
                                                Optional<CoachProfileDto> profile) {
        ObjectNode payload = json.createObjectNode();
        payload.put("mode", "reflect");
        payload.put("userText", userText);
        profile.ifPresent(p -> payload.set("profile", compactProfile(p)));

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("subjectNotes", notes.subjectNotes(household, subject).map(this::compactNotes));
        gather.put("memories", memory.recall(household, subject, null, userText)
                .map(hits -> (JsonNode) json.valueToTree(hits)));
        gather.put("recentSessions", store.recentSessions(household, subject, RECENT_SESSIONS_K)
                .map(this::compactSessions));

        return coordinator.coordinate(List.of(manifest.body(), skillBody()), payload, gather,
                LlmChannel.DEFAULT);
    }

    private Mono<IntentResponse> persistAndReply(UUID household, UUID subject, CoordinationResult result) {
        JsonNode parsed = parseJson(result.text());
        if (parsed == null) {
            log.warn("reflect output was not JSON — replying with the raw text, nothing persisted");
            return Mono.just(reply(firstNonBlank(result.text(), REPLY_FALLBACK), result.llmModel()));
        }
        String replyText = firstNonBlank(text(parsed, "reply"), REPLY_FALLBACK);
        return persist(household, subject, parsed)
                .onErrorResume(e -> {
                    log.warn("coach record persist failed for subject={}: {}", subject, e.toString());
                    return Mono.empty();
                })
                .thenReturn(reply(replyText, result.llmModel()));
    }

    /** Session envelope first (observations hang off its id), then observations, then hypotheses. */
    private Mono<Void> persist(UUID household, UUID subject, JsonNode parsed) {
        String summary = text(parsed, "sessionSummary");
        return store.startSession(new StartCoachSessionInput(household, subject, "reflect", summary))
                .flatMap(session -> persistObservations(household, subject, session, parsed)
                        .then(persistHypotheses(household, subject, parsed)));
    }

    private Mono<Void> persistObservations(UUID household, UUID subject, CoachSessionDto session,
                                           JsonNode parsed) {
        List<AddCoachObservationInput> inputs = new ArrayList<>();
        for (JsonNode o : array(parsed, "observations")) {
            String text = text(o, "text");
            String method = normalizeMethod(text(o, "method"));
            if (text == null) {
                continue;
            }
            if (method == null) {
                log.warn("dropping observation with unknown method '{}'", text(o, "method"));
                continue;
            }
            inputs.add(new AddCoachObservationInput(household, subject, session.id(), text, method, null));
        }
        return Flux.fromIterable(inputs).concatMap(store::addObservation).then();
    }

    private Mono<Void> persistHypotheses(UUID household, UUID subject, JsonNode parsed) {
        List<AddCoachHypothesisInput> inputs = new ArrayList<>();
        for (JsonNode h : array(parsed, "hypotheses")) {
            String text = text(h, "text");
            if (text == null) {
                continue;
            }
            inputs.add(new AddCoachHypothesisInput(household, subject, text, confidence(h), null, null));
        }
        return Flux.fromIterable(inputs).concatMap(store::addHypothesis).then();
    }

    /** Only the vector fields the prompt needs — never the row ids. */
    private ObjectNode compactProfile(CoachProfileDto p) {
        ObjectNode node = json.createObjectNode();
        if (p.methodWeights() != null) node.set("methodWeights", p.methodWeights());
        if (p.tone() != null) node.put("tone", p.tone());
        if (p.focusAreas() != null) node.set("focusAreas", p.focusAreas());
        if (p.boundaries() != null) node.set("boundaries", p.boundaries());
        return node;
    }

    private JsonNode compactNotes(List<NoteDto> list) {
        ArrayNode arr = json.createArrayNode();
        for (NoteDto n : list) {
            ObjectNode node = arr.addObject();
            node.put("title", n.title());
            node.put("type", n.type());
            node.put("body", truncate(n.bodyMd()));
            if (n.updatedAt() != null) node.put("updatedAt", n.updatedAt().toString());
        }
        return arr;
    }

    private JsonNode compactSessions(List<CoachSessionDto> list) {
        ArrayNode arr = json.createArrayNode();
        for (CoachSessionDto s : list) {
            ObjectNode node = arr.addObject();
            node.put("mode", s.mode());
            if (s.summary() != null) node.put("summary", s.summary());
            if (s.createdAt() != null) node.put("at", s.createdAt().toString());
        }
        return arr;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= NOTE_BODY_MAX_CHARS ? body : body.substring(0, NOTE_BODY_MAX_CHARS) + "…";
    }

    private static String normalizeMethod(String method) {
        if (method == null) {
            return null;
        }
        String m = method.trim().toLowerCase(Locale.ROOT);
        return METHODS.contains(m) ? m : null;
    }

    /** Clamped 0–100 integer, or null when absent/non-numeric. */
    private static Integer confidence(JsonNode h) {
        JsonNode c = h.get("confidence");
        if (c == null || !c.isNumber()) {
            return null;
        }
        return Math.max(0, Math.min(100, c.asInt()));
    }

    /** Lenient JSON extraction: tolerate markdown fences / prose around the object. */
    private JsonNode parseJson(String content) {
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
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Iterable<JsonNode> array(JsonNode parsed, String field) {
        JsonNode node = parsed.get(field);
        return (node != null && node.isArray()) ? node : List.of();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return v.isBlank() ? null : v;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "reflect SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
