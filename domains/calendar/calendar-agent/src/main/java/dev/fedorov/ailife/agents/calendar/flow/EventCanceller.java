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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The calendar domain's user-facing <b>event-cancel</b> path (road-test #486, Track H.2 / HC-3): cancel an
 * already-scheduled event by just chatting ("отмени встречу с врачом"), behind the standing
 * <b>confirm-before-delete</b> gate (a calendar principle; see AGENT.md). Routed here by
 * {@link dev.fedorov.ailife.agents.calendar.intent.CalendarIntentRouter} as the {@code event-cancel} intent
 * skill.
 *
 * <p>Two turns, over the Stage-4 pending-action lock:
 * <ol>
 *   <li>{@link #cancel} — read the owner's upcoming events (their personal ∪ shared households), let the LLM
 *       ({@code event-cancel} SKILL, temperature 0) pick which candidate they mean, and reply with a
 *       {@code pendingAction} asking them to confirm the deletion (so the orchestrator route-locks to
 *       calendar). Nothing is deleted yet. An unresolvable target asks / lists instead of guessing.</li>
 *   <li>{@link #resume} — on the reply: an affirmative deletes the stashed event via mcp-caldav's
 *       {@code DELETE /internal/event/{id}} ({@link CaldavEventClient#deleteEvent}); anything else leaves it.
 *       Either reply carries no {@code pendingAction}, clearing the lock.</li>
 * </ol>
 * Every stage soft-fails to a friendly reply; mcp-caldav stays tenant-agnostic.
 */
@Component
public class EventCanceller {

    private static final Logger log = LoggerFactory.getLogger(EventCanceller.class);
    public static final String SKILL_NAME = "event-cancel";
    /** pendingAction discriminator the calendar ResumeController dispatches on. */
    public static final String FLOW = "event-cancel-confirm";
    /** How far back / forward to look for the event to cancel (cancellation targets upcoming events). */
    private static final Duration LOOK_BACK = Duration.ofDays(1);
    private static final Duration LOOK_AHEAD = Duration.ofDays(180);
    private static final int MAX_CANDIDATES = 40;
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneOffset.UTC);
    private static final Set<String> AFFIRMATIVE = Set.of(
            "да", "ага", "верно", "отмени", "отменить", "удали", "удалить", "ок", "окей", "давай", "+",
            "yes", "y", "ok", "confirm", "cancel");

    private final LlmClient llm;
    private final CaldavEventClient caldav;
    private final ProfileClient profile;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public EventCanceller(LlmClient llm, CaldavEventClient caldav, ProfileClient profile,
                          SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.llm = llm;
        this.caldav = caldav;
        this.profile = profile;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> cancel(NormalizedMessage msg) {
        String userText = msg == null ? null : msg.text();
        if (userText == null || userText.isBlank()) {
            return Mono.just(reply("Какое событие отменить?", null));
        }
        Instant now = Instant.now();
        return readHouseholds(msg)
                .flatMap(households -> households.isEmpty()
                        ? Mono.just(reply("Не понял, в каком календаре искать событие.", null))
                        : caldav.eventsInWindow(households, now.minus(LOOK_BACK), now.plus(LOOK_AHEAD))
                        .flatMap(events -> resolveAndConfirm(userText, now, events)))
                .onErrorResume(e -> {
                    log.warn("event-cancel failed: {}", e.toString());
                    return Mono.just(reply("Не смог найти событие для отмены. Попробуйте ещё раз позже.", null));
                });
    }

    private Mono<IntentResponse> resolveAndConfirm(String userText, Instant now, List<CalendarEventDto> events) {
        if (events == null || events.isEmpty()) {
            return Mono.just(reply("Не нашёл предстоящих событий, которые можно отменить.", null));
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

        return llm.chat(req).map(resp -> pickReply(parsePick(resp.content()), candidates, resp.model()));
    }

    private IntentResponse pickReply(Pick pick, List<CalendarEventDto> candidates, String model) {
        if (pick == null || pick.indices().isEmpty()) {
            return reply("Не нашёл такое событие в календаре. Уточните, что отменить.", model);
        }
        if (pick.indices().size() > 1) {
            StringBuilder sb = new StringBuilder("Нашёл несколько подходящих событий — какое отменить?");
            for (int i : pick.indices()) {
                CalendarEventDto e = candidateAt(candidates, i);
                if (e != null) {
                    sb.append("\n• «").append(safeSummary(e)).append("»").append(whenSuffix(e.dtstart()));
                }
            }
            return reply(sb.toString(), model);
        }
        CalendarEventDto target = candidateAt(candidates, pick.indices().get(0));
        if (target == null) {
            return reply("Не нашёл такое событие в календаре. Уточните, что отменить.", model);
        }
        String summary = safeSummary(target);
        String confirm = "Отменить «" + summary + "»" + whenSuffix(target.dtstart())
                + "? Ответьте «да», чтобы удалить.";
        return new IntentResponse(manifest.name(), confirm, model, pendingAction(target.id(), summary));
    }

    /**
     * Resume after the user replies to the confirmation. Affirmative → delete the stashed event; anything
     * else → leave it. Either reply carries no pendingAction, so the orchestrator clears the lock.
     */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        JsonNode pending = req.pendingAction();
        UUID eventId = eventId(pending);
        if (eventId == null) {
            return Mono.just(reply("Нечего отменять — повторите запрос, пожалуйста.", null));
        }
        String summary = pending.path("summary").asString("событие");
        String text = req.message() == null ? null : req.message().text();
        if (!isAffirmative(text)) {
            return Mono.just(reply("Оставил «" + summary + "» без изменений.", null));
        }
        return caldav.deleteEvent(eventId)
                .then(Mono.just(reply("Отменил «" + summary + "».", null)))
                .onErrorResume(e -> {
                    log.warn("event-cancel delete failed for {}: {}", eventId, e.toString());
                    return Mono.just(reply(
                            "Не смог отменить «" + summary + "» — возможно, событие уже удалено.", null));
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

    /** Parse the LLM selection: {"pick": n} | {"ambiguous": [n, ...]} | {} (none). 1-based → indices. */
    private Pick parsePick(String raw) {
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
            return new Pick(List.of(node.get("pick").asInt()));
        }
        JsonNode ambiguous = node.get("ambiguous");
        if (ambiguous != null && ambiguous.isArray()) {
            List<Integer> ns = new ArrayList<>();
            ambiguous.forEach(n -> {
                if (n.isNumber()) {
                    ns.add(n.asInt());
                }
            });
            return new Pick(ns);
        }
        return null;
    }

    /** One candidate by its 1-based LLM index; null when out of range. */
    private static CalendarEventDto candidateAt(List<CalendarEventDto> candidates, int oneBased) {
        int i = oneBased - 1;
        return (i >= 0 && i < candidates.size()) ? candidates.get(i) : null;
    }

    private JsonNode pendingAction(UUID eventId, String summary) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", FLOW);
        node.put("eventId", eventId.toString());
        node.put("summary", summary);
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

    private static boolean isAffirmative(String text) {
        return text != null && AFFIRMATIVE.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    private static String safeSummary(CalendarEventDto e) {
        return (e.summary() != null && !e.summary().isBlank()) ? e.summary() : "событие";
    }

    private static String whenSuffix(Instant dtstart) {
        return dtstart != null ? " на " + WHEN.format(dtstart) : "";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "event-cancel SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /** The resolved selection: one index (pick), several (ambiguous), or empty (none). */
    private record Pick(List<Integer> indices) {
    }
}
