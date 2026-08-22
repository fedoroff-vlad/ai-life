package dev.fedorov.ailife.agents.calendar.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.calendar.http.CaldavEventClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.calendar.UpdateEventInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The calendar domain's user-facing <b>event-move</b> path (road-test #486, Track H.2 / HC-4): reschedule an
 * already-scheduled event by just chatting ("перенеси встречу с врачом на 16:00"), behind a
 * <b>confirm-before-change</b> gate (the target is resolved by fuzzy match, so the same wrong-target risk as
 * {@link EventCanceller} applies). Routed here by
 * {@link dev.fedorov.ailife.agents.calendar.intent.CalendarIntentRouter} as the {@code event-move} intent
 * skill.
 *
 * <p>Two turns, over the Stage-4 pending-action lock:
 * <ol>
 *   <li>{@link #move} — read the owner's upcoming events (their personal ∪ shared households), let the LLM
 *       ({@code event-move} SKILL, temperature 0) pick the target and resolve the new time, and reply with a
 *       {@code pendingAction} asking to confirm the reschedule (route-locks to calendar). Nothing changes
 *       yet. A picked target with no new time asks for it; an unresolvable target asks / lists.</li>
 *   <li>{@link #resume} — on the reply: an affirmative patches only the time via mcp-caldav's
 *       {@code PUT /internal/event/{id}} ({@link CaldavEventClient#updateEvent}); anything else leaves it.
 *       Either reply clears the lock.</li>
 * </ol>
 * Every stage soft-fails; mcp-caldav stays tenant-agnostic.
 */
@Component
public class EventMover {

    private static final Logger log = LoggerFactory.getLogger(EventMover.class);
    public static final String SKILL_NAME = "event-move";
    /** pendingAction discriminator the calendar ResumeController dispatches on. */
    public static final String FLOW = "event-move-confirm";
    private static final Duration LOOK_BACK = Duration.ofDays(1);
    private static final Duration LOOK_AHEAD = Duration.ofDays(180);
    private static final int MAX_CANDIDATES = 40;
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneOffset.UTC);
    private static final Set<String> AFFIRMATIVE = Set.of(
            "да", "ага", "верно", "перенеси", "перенести", "ок", "окей", "давай", "+",
            "yes", "y", "ok", "confirm", "move");

    private final LlmClient llm;
    private final CaldavEventClient caldav;
    private final ProfileClient profile;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public EventMover(LlmClient llm, CaldavEventClient caldav, ProfileClient profile,
                      SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.llm = llm;
        this.caldav = caldav;
        this.profile = profile;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> move(NormalizedMessage msg) {
        String userText = msg == null ? null : msg.text();
        if (userText == null || userText.isBlank()) {
            return Mono.just(reply("Какое событие перенести и на когда?", null));
        }
        Instant now = Instant.now();
        return readHouseholds(msg)
                .flatMap(households -> households.isEmpty()
                        ? Mono.just(reply("Не понял, в каком календаре искать событие.", null))
                        : caldav.eventsInWindow(households, now.minus(LOOK_BACK), now.plus(LOOK_AHEAD))
                        .flatMap(events -> resolveAndConfirm(userText, now, events)))
                .onErrorResume(e -> {
                    log.warn("event-move failed: {}", e.toString());
                    return Mono.just(reply("Не смог перенести событие. Попробуйте ещё раз позже.", null));
                });
    }

    private Mono<IntentResponse> resolveAndConfirm(String userText, Instant now, List<CalendarEventDto> events) {
        if (events == null || events.isEmpty()) {
            return Mono.just(reply("Не нашёл предстоящих событий, которые можно перенести.", null));
        }
        List<CalendarEventDto> candidates = events.size() > MAX_CANDIDATES
                ? events.subList(0, MAX_CANDIDATES) : events;

        ObjectNode userMsg = json.createObjectNode();
        userMsg.put("userText", userText);
        userMsg.put("now", now.toString());
        userMsg.set("candidates", candidateList(candidates));

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(userMsg.toString())), 0.0);

        return llm.chat(req).map(resp -> pickReply(parseMove(resp.content()), candidates, resp.model()));
    }

    private IntentResponse pickReply(Move move, List<CalendarEventDto> candidates, String model) {
        if (move == null || move.indices().isEmpty()) {
            return reply("Не нашёл такое событие в календаре. Уточните, что перенести.", model);
        }
        if (move.indices().size() > 1) {
            StringBuilder sb = new StringBuilder("Нашёл несколько подходящих событий — какое перенести?");
            for (int i : move.indices()) {
                CalendarEventDto e = candidateAt(candidates, i);
                if (e != null) {
                    sb.append("\n• «").append(safeSummary(e)).append("»").append(at(e.dtstart()));
                }
            }
            return reply(sb.toString(), model);
        }
        CalendarEventDto target = candidateAt(candidates, move.indices().get(0));
        if (target == null) {
            return reply("Не нашёл такое событие в календаре. Уточните, что перенести.", model);
        }
        String summary = safeSummary(target);
        if (move.dtstart() == null) {
            return reply("На какое время перенести «" + summary + "»" + at(target.dtstart()) + "?", model);
        }
        String from = target.dtstart() != null ? " с " + WHEN.format(target.dtstart()) : "";
        String confirm = "Перенести «" + summary + "»" + from
                + " на " + WHEN.format(move.dtstart()) + "? Ответьте «да», чтобы перенести.";
        return new IntentResponse(manifest.name(), confirm, model,
                pendingAction(target.id(), summary, move.dtstart(), move.dtend()));
    }

    /**
     * Resume after the user replies to the confirmation. Affirmative → patch only the time via mcp-caldav;
     * anything else → leave it. Either reply carries no pendingAction, so the orchestrator clears the lock.
     */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        JsonNode pending = req.pendingAction();
        UUID eventId = eventId(pending);
        Instant dtstart = instant(pending, "dtstart");
        if (eventId == null || dtstart == null) {
            return Mono.just(reply("Нечего переносить — повторите запрос, пожалуйста.", null));
        }
        String summary = pending.path("summary").asString("событие");
        String text = req.message() == null ? null : req.message().text();
        if (!isAffirmative(text)) {
            return Mono.just(reply("Оставил «" + summary + "» без изменений.", null));
        }
        Instant dtend = instant(pending, "dtend");
        UpdateEventInput input = new UpdateEventInput(eventId, null, null, null, dtstart, dtend, null, null);
        return caldav.updateEvent(eventId, input)
                .map(dto -> reply("Перенёс «" + summary + "» на " + WHEN.format(dtstart) + ".", null))
                .onErrorResume(e -> {
                    log.warn("event-move update failed for {}: {}", eventId, e.toString());
                    return Mono.just(reply(
                            "Не смог перенести «" + summary + "» — возможно, событие уже удалено.", null));
                });
    }

    /** The caller's read set: personal ∪ shared households, else the envelope household (no userId / 404). */
    private Mono<List<UUID>> readHouseholds(NormalizedMessage msg) {
        UUID envelope = msg.householdId();
        List<UUID> fallback = envelope == null ? List.of() : List.of(envelope);
        if (msg.userId() == null) {
            return Mono.just(fallback);
        }
        return profile.householdRouting(msg.userId())
                .map(routing -> {
                    List<UUID> set = new ArrayList<>();
                    if (routing.personalHouseholdId() != null) {
                        set.add(routing.personalHouseholdId());
                    }
                    if (routing.sharedHouseholdIds() != null) {
                        routing.sharedHouseholdIds().stream().filter(h -> h != null && !set.contains(h))
                                .forEach(set::add);
                    }
                    return set.isEmpty() ? fallback : List.copyOf(set);
                })
                .onErrorReturn(fallback)
                .defaultIfEmpty(fallback);
    }

    /** The numbered candidate list handed to the LLM ({@code {n, summary, dtstart}}). */
    private ArrayNode candidateList(List<CalendarEventDto> candidates) {
        ArrayNode arr = json.createArrayNode();
        for (int i = 0; i < candidates.size(); i++) {
            CalendarEventDto e = candidates.get(i);
            ObjectNode node = json.createObjectNode();
            node.put("n", i + 1);
            node.put("summary", safeSummary(e));
            if (e.dtstart() != null) {
                node.put("dtstart", e.dtstart().toString());
            }
            arr.add(node);
        }
        return arr;
    }

    /** Parse the LLM selection: {"pick":n,"dtstart":…,"dtend":…} | {"ambiguous":[…]} | {} (none). */
    private Move parseMove(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode node;
        try {
            node = json.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
        if (node.hasNonNull("pick") && node.get("pick").isNumber()) {
            Instant dtstart = instant(node, "dtstart");
            Instant dtend = instant(node, "dtend");
            if (dtend != null && dtstart != null && !dtend.isAfter(dtstart)) {
                dtend = null;   // never file a non-positive span
            }
            return new Move(List.of(node.get("pick").asInt()), dtstart, dtend);
        }
        JsonNode ambiguous = node.get("ambiguous");
        if (ambiguous != null && ambiguous.isArray()) {
            List<Integer> ns = new ArrayList<>();
            ambiguous.forEach(n -> {
                if (n.isNumber()) {
                    ns.add(n.asInt());
                }
            });
            return new Move(ns, null, null);
        }
        return null;
    }

    /** One candidate by its 1-based LLM index; null when out of range. */
    private static CalendarEventDto candidateAt(List<CalendarEventDto> candidates, int oneBased) {
        int i = oneBased - 1;
        return (i >= 0 && i < candidates.size()) ? candidates.get(i) : null;
    }

    private JsonNode pendingAction(UUID eventId, String summary, Instant dtstart, Instant dtend) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", FLOW);
        node.put("eventId", eventId.toString());
        node.put("summary", summary);
        node.put("dtstart", dtstart.toString());
        if (dtend != null) {
            node.put("dtend", dtend.toString());
        }
        return node;
    }

    private static UUID eventId(JsonNode pending) {
        if (pending == null || !pending.hasNonNull("eventId")) {
            return null;
        }
        try {
            return UUID.fromString(pending.get("eventId").asString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Instant instant(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        try {
            return Instant.parse(node.get(field).asString().trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean isAffirmative(String text) {
        return text != null && AFFIRMATIVE.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    private static String safeSummary(CalendarEventDto e) {
        return (e.summary() != null && !e.summary().isBlank()) ? e.summary() : "событие";
    }

    private static String at(Instant dtstart) {
        return dtstart != null ? " на " + WHEN.format(dtstart) : "";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "event-move SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /** The resolved selection: index(es) + the new start/end (null start = target clear but time missing). */
    private record Move(List<Integer> indices, Instant dtstart, Instant dtend) {
    }
}
