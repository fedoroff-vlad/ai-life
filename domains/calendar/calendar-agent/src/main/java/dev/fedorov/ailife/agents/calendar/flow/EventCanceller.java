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
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The calendar domain's user-facing <b>event-cancel</b> path (road-test #486, Track H.2 / HC-3): cancel an
 * already-scheduled event by just chatting ("отмени встречу с врачом"), behind the standing
 * <b>confirm-before-delete</b> gate (a calendar principle; see AGENT.md). Routed here by
 * {@link dev.fedorov.ailife.agents.calendar.intent.CalendarIntentRouter} as the {@code event-cancel} intent
 * skill; the confirming reply routes back through {@code ResumeController}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this class
 * is the calendar-cancel adapter — it reads the candidate pool (upcoming events, personal ∪ shared via
 * {@link ProfileClient#householdRouting}), renders a candidate ({@link CandidateView}), supplies the
 * calendar wording ({@link Phrasing}), and performs the delete ({@link #act} via mcp-caldav
 * {@code DELETE /internal/event/{id}}). mcp-caldav stays tenant-agnostic.
 */
@Component
public class EventCanceller
        implements TargetedActionFlow<CalendarEventDto>, CandidateView<CalendarEventDto>, Phrasing<CalendarEventDto> {

    public static final String SKILL_NAME = "event-cancel";
    /** pendingAction discriminator the calendar ResumeController dispatches on. */
    public static final String FLOW = "event-cancel-confirm";
    /** How far back / forward to look for the event to cancel (cancellation targets upcoming events). */
    private static final Duration LOOK_BACK = Duration.ofDays(1);
    private static final Duration LOOK_AHEAD = Duration.ofDays(180);
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm", java.util.Locale.forLanguageTag("ru")).withZone(ZoneOffset.UTC);

    private final CaldavEventClient caldav;
    private final ProfileClient profile;
    private final PickConfirmActRunner<CalendarEventDto> runner;

    public EventCanceller(LlmClient llm, CaldavEventClient caldav, ProfileClient profile,
                          SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.caldav = caldav;
        this.profile = profile;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the owner's upcoming events, let the LLM pick, and reply with a confirm {@code pendingAction}. */
    public Mono<IntentResponse> cancel(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative deletes the stashed event; anything else leaves it. */
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

    /** Households come from the userId routing, not the envelope, so a null householdId must not short-circuit. */
    @Override
    public boolean requiresHousehold() {
        return false;
    }

    @Override
    public Set<String> extraAffirmatives() {
        return Set.of("отмени", "отменить", "cancel");
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

    /** The SKILL anchors "upcoming" on the current instant. */
    @Override
    public void decorateUserMessage(ObjectNode userMsg) {
        userMsg.put("now", Instant.now().toString());
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode pending) {
        return caldav.deleteEvent(targetId).then();
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
        return "Какое событие отменить?";
    }

    @Override
    public String noHousehold() {
        return "Не понял, в каком календаре искать событие.";
    }

    @Override
    public String emptyPool() {
        return "Не нашёл предстоящих событий, которые можно отменить.";
    }

    @Override
    public String noMatch() {
        return "Не нашёл такое событие в календаре. Уточните, что отменить.";
    }

    @Override
    public String readFailed() {
        return "Не смог найти событие для отмены. Попробуйте ещё раз позже.";
    }

    @Override
    public String notReady() {
        return "Нечего отменять — повторите запрос, пожалуйста.";
    }

    @Override
    public String ambiguous(List<CalendarEventDto> picks) {
        StringBuilder sb = new StringBuilder("Нашёл несколько подходящих событий — какое отменить?");
        for (CalendarEventDto e : picks) {
            sb.append("\n• «").append(safeSummary(e)).append("»").append(whenSuffix(e.dtstart()));
        }
        return sb.toString();
    }

    @Override
    public String confirm(CalendarEventDto target, JsonNode pick) {
        return "Отменить «" + safeSummary(target) + "»" + whenSuffix(target.dtstart())
                + "? Ответьте «да», чтобы удалить.";
    }

    @Override
    public String declined(JsonNode pending) {
        return "Оставил «" + summary(pending) + "» без изменений.";
    }

    @Override
    public String done(JsonNode pending) {
        return "Отменил «" + summary(pending) + "».";
    }

    @Override
    public String actFailed(JsonNode pending) {
        return "Не смог отменить «" + summary(pending) + "» — возможно, событие уже удалено.";
    }

    private static String summary(JsonNode pending) {
        return pending.path("summary").asString("событие");
    }

    private static String safeSummary(CalendarEventDto e) {
        return (e.summary() != null && !e.summary().isBlank()) ? e.summary() : "событие";
    }

    private static String whenSuffix(Instant dtstart) {
        return dtstart != null ? " на " + WHEN.format(dtstart) : "";
    }
}
