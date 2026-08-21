package dev.fedorov.ailife.agents.calendar.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.calendar.http.CaldavEventClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.calendar.CreateEventInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.sharing.SharingContext;
import dev.fedorov.ailife.sharing.SharingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The calendar domain's user-facing <b>event-capture</b> path (road-test #486, Track H.2 / HC-1): turn a
 * plain-language scheduling request ("запиши встречу с врачом завтра в 15:00") into a calendar event.
 * Routed here by {@link dev.fedorov.ailife.agents.calendar.intent.CalendarIntentRouter} as the
 * {@code event-capture} intent skill — the first way to create an event by just chatting (until now events
 * came only from the inter-agent {@code create_event} action).
 *
 * <p>Flow (mirrors tasks' {@code TaskCapturer} / notes' {@code NoteWriter}):
 * <ol>
 *   <li>ask the LLM — with the {@code event-capture} SKILL as the instruction and the current instant as
 *       {@code now} — for a strict-JSON plan ({@code {summary, dtstart, dtend?}}), temperature 0 (parsing a
 *       time must be faithful, not creative);</li>
 *   <li>an empty plan (no resolvable time) → ask for the time rather than filing a wrong-time event (the
 *       standing "clarify ambiguous time" principle);</li>
 *   <li>resolve the household with the shared {@link SharingResolver} exactly as {@code create_event} does
 *       (no explicit scope → calendar's private default), then create via mcp-caldav's {@code /internal/event}
 *       ({@link CaldavEventClient#createEvent}) and confirm.</li>
 * </ol>
 * Every stage soft-fails to a friendly reply. mcp-caldav stays tenant-agnostic. Timezone handling is MVP:
 * the model carries {@code now}'s offset; per-user tzid from profile is a documented later refinement.
 */
@Component
public class EventCapturer {

    private static final Logger log = LoggerFactory.getLogger(EventCapturer.class);
    public static final String SKILL_NAME = "event-capture";
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneOffset.UTC);

    private final LlmClient llm;
    private final CaldavEventClient caldav;
    private final SharingResolver sharing;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public EventCapturer(LlmClient llm, CaldavEventClient caldav, SharingResolver sharing,
                         SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.llm = llm;
        this.caldav = caldav;
        this.sharing = sharing;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> capture(NormalizedMessage msg) {
        String userText = msg == null ? null : msg.text();
        if (userText == null || userText.isBlank()) {
            return Mono.just(reply("Что записать в календарь? Например: «встреча с врачом завтра в 15:00».", null));
        }
        ObjectNode userMsg = json.createObjectNode();
        userMsg.put("userText", userText);
        userMsg.put("now", Instant.now().toString());

        // temperature 0: resolving a date/time must be faithful to the user, not creative.
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(userMsg.toString())), 0.0);

        return llm.chat(req)
                .flatMap(resp -> planAndCreate(msg, resp.content(), resp.model()))
                .onErrorResume(e -> {
                    log.warn("event-capture failed: {}", e.toString());
                    return Mono.just(reply("Не смог записать событие. Попробуйте ещё раз чуть позже.", null));
                });
    }

    private Mono<IntentResponse> planAndCreate(NormalizedMessage msg, String content, String model) {
        Planned plan = parsePlan(content);
        if (plan == null) {
            return Mono.just(reply(
                    "Когда назначить? Уточните дату и время — например, «завтра в 15:00».", model));
        }
        UUID envelopeHousehold = msg.householdId();
        // No explicit scope → calendar's default-sharing policy (private) decides, resolved to a concrete
        // personal/family household by the shared resolver — same path create_event takes.
        return sharing.resolveHousehold(msg.userId(), null, SharingContext.ofCategories(null), envelopeHousehold)
                .flatMap(household -> caldav.createEvent(new CreateEventInput(
                                household, plan.summary(), null, null, plan.dtstart(), plan.dtend(),
                                null, null, null, null))
                        .map(dto -> reply(confirm(dto.summary(), dto.dtstart()), model)))
                .switchIfEmpty(Mono.just(reply("Не понял, в какой календарь записать событие.", model)));
    }

    /** Parse the single-event LLM plan; null when no event with a resolvable time was described. */
    private Planned parsePlan(String raw) {
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
        String summary = node.path("summary").asString("").trim();
        Instant dtstart = instant(node, "dtstart");
        if (summary.isEmpty() || dtstart == null) {
            return null;   // empty {} or missing time → ask for the time
        }
        Instant dtend = instant(node, "dtend");
        if (dtend != null && !dtend.isAfter(dtstart)) {
            dtend = null;  // never file a non-positive span; treat as a point event
        }
        return new Planned(summary, dtstart, dtend);
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

    private static String confirm(String summary, Instant dtstart) {
        String when = dtstart != null ? " на " + WHEN.format(dtstart) : "";
        return "Записал в календарь: «" + summary + "»" + when + ".";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "event-capture SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /** One planned event: a title, a start instant, and an optional end (null for a point-in-time event). */
    private record Planned(String summary, Instant dtstart, Instant dtend) {
    }
}
