package dev.fedorov.ailife.agents.calendar.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Phrasing;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.calendar.http.CaldavEventClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.calendar.UpdateEventInput;
import dev.fedorov.ailife.llm.LlmClient;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The calendar domain's user-facing <b>event-move</b> path (road-test #486, Track H.2 / HC-4): reschedule an
 * already-scheduled event by just chatting ("перенеси встречу с врачом на 16:00"), behind a
 * <b>confirm-before-change</b> gate. Routed here by
 * {@link dev.fedorov.ailife.agents.calendar.intent.CalendarIntentRouter} as the {@code event-move} intent
 * skill; the confirming reply routes back through {@code ResumeController}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this class
 * is the calendar-move adapter and the first non-delete consumer — it exercises the runner's move seams: the
 * new time is threaded through the {@code pendingAction} (via the LLM selection node), a picked target with
 * no time re-asks ({@link #missing}), the resume needs both the id and the stashed time
 * ({@link #readyToAct}), and {@link #act} patches only the time via mcp-caldav {@code PUT /internal/event/{id}}.
 */
@Component
public class EventMover
        implements TargetedActionFlow<CalendarEventDto>, CandidateView<CalendarEventDto>, Phrasing<CalendarEventDto> {

    public static final String SKILL_NAME = "event-move";
    /** pendingAction discriminator the calendar ResumeController dispatches on. */
    public static final String FLOW = "event-move-confirm";
    private static final Duration LOOK_BACK = Duration.ofDays(1);
    private static final Duration LOOK_AHEAD = Duration.ofDays(180);
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneOffset.UTC);

    private final CaldavEventClient caldav;
    private final ProfileClient profile;
    private final PickConfirmActRunner<CalendarEventDto> runner;

    public EventMover(LlmClient llm, CaldavEventClient caldav, ProfileClient profile,
                      SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.caldav = caldav;
        this.profile = profile;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the owner's upcoming events, let the LLM pick the target + new time, and reply with a confirm. */
    public Mono<IntentResponse> move(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative patches only the time; anything else leaves it. */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        return runner.resume(req);
    }

    // ----- TargetedActionFlow -----------------------------------------------------------------------

    @Override
    public String skillName() {
        return SKILL_NAME;
    }

    @Override
    public String flow() {
        return FLOW;
    }

    @Override
    public String idField() {
        return "eventId";
    }

    @Override
    public String labelField() {
        return "summary";
    }

    @Override
    public boolean requiresHousehold() {
        return false;
    }

    @Override
    public Set<String> extraAffirmatives() {
        return Set.of("перенеси", "перенести", "move");
    }

    @Override
    public Mono<List<CalendarEventDto>> candidates(NormalizedMessage msg) {
        Instant now = Instant.now();
        return readHouseholds(msg).flatMap(households -> households.isEmpty()
                ? Mono.just(List.of())
                : caldav.eventsInWindow(households, now.minus(LOOK_BACK), now.plus(LOOK_AHEAD)));
    }

    @Override
    public CandidateView<CalendarEventDto> view() {
        return this;
    }

    @Override
    public Phrasing<CalendarEventDto> phrasing() {
        return this;
    }

    /** The SKILL resolves relative times ("на завтра") against the current instant. */
    @Override
    public void decorateUserMessage(ObjectNode userMsg) {
        userMsg.put("now", Instant.now().toString());
    }

    /** Picked a target but the model gave no new time → ask for it (no lock, no change). */
    @Override
    public Optional<String> missing(CalendarEventDto target, JsonNode pick) {
        return instant(pick, "dtstart") == null
                ? Optional.of("На какое время перенести «" + safeSummary(target) + "»" + at(target.dtstart()) + "?")
                : Optional.empty();
    }

    /** The resume needs the stashed new start, not just the id. */
    @Override
    public boolean readyToAct(JsonNode pending) {
        return instant(pending, "dtstart") != null;
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode pending) {
        Instant dtstart = instant(pending, "dtstart");
        Instant dtend = instant(pending, "dtend");
        if (dtend != null && dtstart != null && !dtend.isAfter(dtstart)) {
            dtend = null;   // never file a non-positive span
        }
        UpdateEventInput input = new UpdateEventInput(targetId, null, null, null, dtstart, dtend, null, null);
        return caldav.updateEvent(targetId, input).then();
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

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(CalendarEventDto e) {
        return e.id();
    }

    /** The stored label = bare summary; the phrasing adds its own «…» + time. */
    @Override
    public String label(CalendarEventDto e) {
        return safeSummary(e);
    }

    @Override
    public void describe(ObjectNode node, CalendarEventDto e) {
        node.put("summary", safeSummary(e));
        if (e.dtstart() != null) {
            node.put("dtstart", e.dtstart().toString());
        }
    }

    // ----- Phrasing ---------------------------------------------------------------------------------

    @Override
    public String askWhich() {
        return "Какое событие перенести и на когда?";
    }

    @Override
    public String noHousehold() {
        return "Не понял, в каком календаре искать событие.";
    }

    @Override
    public String emptyPool() {
        return "Не нашёл предстоящих событий, которые можно перенести.";
    }

    @Override
    public String noMatch() {
        return "Не нашёл такое событие в календаре. Уточните, что перенести.";
    }

    @Override
    public String readFailed() {
        return "Не смог перенести событие. Попробуйте ещё раз позже.";
    }

    @Override
    public String notReady() {
        return "Нечего переносить — повторите запрос, пожалуйста.";
    }

    @Override
    public String ambiguous(List<CalendarEventDto> picks) {
        StringBuilder sb = new StringBuilder("Нашёл несколько подходящих событий — какое перенести?");
        for (CalendarEventDto e : picks) {
            sb.append("\n• «").append(safeSummary(e)).append("»").append(at(e.dtstart()));
        }
        return sb.toString();
    }

    @Override
    public String confirm(CalendarEventDto target, JsonNode pick) {
        Instant dtstart = instant(pick, "dtstart");
        String from = target.dtstart() != null ? " с " + WHEN.format(target.dtstart()) : "";
        return "Перенести «" + safeSummary(target) + "»" + from
                + " на " + WHEN.format(dtstart) + "? Ответьте «да», чтобы перенести.";
    }

    @Override
    public String declined(JsonNode pending) {
        return "Оставил «" + summary(pending) + "» без изменений.";
    }

    @Override
    public String done(JsonNode pending) {
        Instant dtstart = instant(pending, "dtstart");
        return "Перенёс «" + summary(pending) + "» на " + WHEN.format(dtstart) + ".";
    }

    @Override
    public String actFailed(JsonNode pending) {
        return "Не смог перенести «" + summary(pending) + "» — возможно, событие уже удалено.";
    }

    private static String summary(JsonNode pending) {
        return pending.path("summary").asString("событие");
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

    private static String safeSummary(CalendarEventDto e) {
        return (e.summary() != null && !e.summary().isBlank()) ? e.summary() : "событие";
    }

    private static String at(Instant dtstart) {
        return dtstart != null ? " на " + WHEN.format(dtstart) : "";
    }
}
