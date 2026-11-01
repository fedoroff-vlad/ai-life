package dev.fedorov.ailife.agents.calendar.flow;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.calendar.http.CaldavEventClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The calendar domain's user-facing <b>read</b> path: answer what is already on the calendar for a period
 * the user names ("что у меня в календаре", "какие события на следующей неделе"). Routed here by
 * {@link dev.fedorov.ailife.agents.calendar.intent.CalendarIntentRouter} as the {@code event-list} intent
 * skill.
 *
 * <p>Until this flow existed the agent could only create, move, cancel or chat, so a read question was
 * classified into the nearest write skill and came back as "Когда назначить?" — the classifier had no right
 * answer to pick. Found on the first live deployment, 2026-11-01.
 *
 * <p>Shape: resolve the caller's read set (personal ∪ shared households, {@link ProfileClient#householdRouting})
 * → read a wide window from the {@code calendar.events_cache} via mcp-caldav {@code GET /internal/events}
 * → hand the events, the user's question, {@code now} and the display timezone to the SKILL, which resolves
 * the period and writes the answer. Read-only: no confirm gate, nothing to undo. The window is the same one
 * {@link EventCanceller} uses, so "what is on my calendar" and "cancel that" see the same events.
 *
 * <p>{@code now} is passed explicitly because nothing else in the prompt carries the current date — without
 * it the model cannot resolve "next week" and starts guessing the year.
 */
@Component
public class AgendaReader {

    public static final String SKILL_NAME = "event-list";
    /** The read window: yesterday (so "что было утром" still resolves) through half a year ahead. */
    private static final Duration LOOK_BACK = Duration.ofDays(1);
    private static final Duration LOOK_AHEAD = Duration.ofDays(180);
    /** Keep the prompt bounded — the SKILL answers with the nearest few anyway. */
    private static final int MAX_EVENTS = 60;

    private final LlmClient llm;
    private final CaldavEventClient caldav;
    private final ProfileClient profile;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final String displayZone;

    public AgendaReader(LlmClient llm, CaldavEventClient caldav, ProfileClient profile, SkillRegistry skills,
                        AgentManifest manifest, ObjectMapper json,
                        @Value("${calendar.display-timezone:Europe/Moscow}") String displayZone) {
        this.llm = llm;
        this.caldav = caldav;
        this.profile = profile;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.displayZone = displayZone;
    }

    /** Read the window, then let the SKILL pick the asked-for period out of it and answer. */
    public Mono<IntentResponse> read(NormalizedMessage msg) {
        Instant now = Instant.now();
        return readHouseholds(msg)
                .flatMap(households -> households.isEmpty()
                        ? Mono.just(List.<CalendarEventDto>of())
                        : caldav.eventsInWindow(households, now.minus(LOOK_BACK), now.plus(LOOK_AHEAD)))
                .onErrorReturn(List.of())
                .flatMap(events -> answer(msg, now, events));
    }

    private Mono<IntentResponse> answer(NormalizedMessage msg, Instant now, List<CalendarEventDto> events) {
        ObjectNode payload = json.createObjectNode();
        payload.put("question", msg.text() == null ? "" : msg.text());
        payload.put("now", now.toString());
        payload.put("timezone", displayZone);
        ArrayNode arr = payload.putArray("events");
        int n = 0;
        for (CalendarEventDto e : events) {
            if (n >= MAX_EVENTS) {
                break;
            }
            ObjectNode node = arr.addObject();
            node.put("n", ++n);
            node.put("summary", e.summary());
            node.put("dtstart", e.dtstart() == null ? null : e.dtstart().toString());
            node.put("dtend", e.dtend() == null ? null : e.dtend().toString());
            node.put("location", e.location());
        }

        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(payload.toString())));
        return llm.chat(request)
                .map(resp -> new IntentResponse(manifest.name(), resp.content(), resp.model()));
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(dev.fedorov.ailife.agentruntime.skill.Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        SKILL_NAME + " SKILL.md not loaded — check skills-classpath"));
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
}
