package dev.fedorov.ailife.agents.notes.flow;

import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agents.notes.config.NotesAgentProperties;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Proactive resurfacing (R-b): on a {@code notes.resurface} wake, pull one stale second-brain note the
 * owner hasn't revisited in a while (memory-service {@code GET /v1/notes/resurface}) and deliver a
 * gentle "you noted this a while ago" reminder — the briefing-style proactive path over the notes
 * substrate. Delivery mirrors the briefing/gift wakes: a note with an {@code ownerId} goes to that user,
 * a household-shared note (null owner) fans out to every household member.
 *
 * <p>Best-effort throughout: nothing stale ({@code 204} → empty) is a silent no-op, and any failure is
 * logged and swallowed so a scheduler wake never errors on a quiet week or a memory-service blip.
 */
@Component
public class NoteResurfacer {

    private static final Logger log = LoggerFactory.getLogger(NoteResurfacer.class);
    /** Cap the note body we quote so a long note doesn't blow up the Telegram message. */
    private static final int SNIPPET = 240;

    private final NoteClient notes;
    private final NotifierClient notifier;
    private final ProfileClient profile;
    private final int olderThanDays;

    public NoteResurfacer(NoteClient notes, NotifierClient notifier, ProfileClient profile,
                          NotesAgentProperties props) {
        this.notes = notes;
        this.notifier = notifier;
        this.profile = profile;
        this.olderThanDays = props.getResurfaceOlderThanDays();
    }

    public Mono<Void> resurface(AgentWakeRequest req) {
        UUID household = req.householdId();
        if (household == null) {
            return Mono.empty();
        }
        return notes.resurface(household, olderThanDays)
                .flatMap(note -> deliver(household, note))   // empty (nothing stale) → no delivery
                .onErrorResume(e -> {
                    log.warn("resurface failed for household={}: {}", household, e.toString());
                    return Mono.empty();
                });
    }

    /** Deliver to the note's owner when set, else fan out to the whole household. */
    private Mono<Void> deliver(UUID household, NoteDto note) {
        String text = format(note);
        if (note.ownerId() != null) {
            return notifyOne(note.ownerId(), text);
        }
        return profile.usersByHousehold(household)
                .flatMap(u -> notifyOne(u.id(), text))
                .then();
    }

    private Mono<Void> notifyOne(UUID userId, String text) {
        return notifier.notify(userId, text)
                .doOnError(e -> log.warn("notify failed for user={}: {}", userId, e.toString()))
                .onErrorResume(e -> Mono.empty());
    }

    /** The deterministic resurfacing message: the note's title, a body snippet, and when it was noted. */
    private static String format(NoteDto note) {
        StringBuilder sb = new StringBuilder("🧠 Из твоих заметок: «").append(note.title()).append("»");
        String body = note.bodyMd();
        if (body != null && !body.isBlank()) {
            String snippet = body.strip();
            if (snippet.length() > SNIPPET) {
                snippet = snippet.substring(0, SNIPPET).strip() + "…";
            }
            sb.append("\n").append(snippet);
        }
        if (note.createdAt() != null) {
            sb.append("\n(записано ").append(note.createdAt().atZone(ZoneOffset.UTC).toLocalDate()).append(")");
        }
        return sb.toString();
    }
}
