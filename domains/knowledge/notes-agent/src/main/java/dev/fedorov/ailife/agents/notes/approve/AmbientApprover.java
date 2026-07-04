package dev.fedorov.ailife.agents.notes.approve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Resolves an <b>ambient-capture approval</b> (AC-4). memory-service noticed an important-but-inferred
 * fact from ordinary chat and, rather than saving it silently, asked the owner "заметил: … — записать?"
 * — setting a conversation route-lock to {@code notes} with the ready-to-write note in the
 * {@code pendingAction}. When the owner replies, the orchestrator resumes us here.
 *
 * <p>An affirmative reply writes the pre-built note via {@link NoteClient} ({@code source=ambient} — all
 * attribution/wiki-linking was done at capture time, so this is a passthrough); anything else drops it.
 * Either way the reply carries no {@code pendingAction}, so the orchestrator clears the lock. Soft-fails
 * to a friendly message and clears the lock (a stuck confirmation is worse than a lost one).
 */
@Component
public class AmbientApprover {

    private static final Logger log = LoggerFactory.getLogger(AmbientApprover.class);

    /** pendingAction discriminator the notes ResumeController dispatches on. */
    public static final String FLOW = "ambient-approve";

    private static final Set<String> AFFIRMATIVE = Set.of(
            "да", "ага", "угу", "верно", "запиши", "записать", "сохрани", "сохранить", "ок", "окей",
            "давай", "конечно", "+", "yes", "y", "ok", "save", "confirm");

    private final NoteClient notes;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public AmbientApprover(NoteClient notes, AgentManifest manifest, ObjectMapper json) {
        this.notes = notes;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> resume(ResumeRequest req) {
        WriteNoteRequest note = parseNote(req.pendingAction());
        if (note == null) {
            return Mono.just(reply("Нечего подтверждать — запись не найдена."));
        }
        String text = req.message() == null ? null : req.message().text();
        if (!isAffirmative(text)) {
            return Mono.just(reply("Хорошо, не записываю."));
        }
        return notes.create(note)
                .map(saved -> reply("Записал: «" + title(note) + "»."))
                .onErrorResume(e -> {
                    log.warn("ambient approval write failed for '{}': {}", title(note), e.toString());
                    return Mono.just(reply("Не удалось сохранить заметку, попробуйте позже."));
                });
    }

    /** The ready-to-write note memory-service stashed under {@code pendingAction.note}, or null. */
    private WriteNoteRequest parseNote(JsonNode pending) {
        JsonNode noteNode = pending == null ? null : pending.get("note");
        if (noteNode == null || !noteNode.isObject()) {
            return null;
        }
        try {
            WriteNoteRequest note = json.treeToValue(noteNode, WriteNoteRequest.class);
            return (note.householdId() == null || note.title() == null || note.title().isBlank())
                    ? null : note;
        } catch (Exception e) {
            log.warn("ambient approval pendingAction unparseable: {}", e.toString());
            return null;
        }
    }

    private static boolean isAffirmative(String text) {
        return text != null && AFFIRMATIVE.contains(text.trim().toLowerCase());
    }

    private static String title(WriteNoteRequest note) {
        return note.title() == null ? "заметка" : note.title().trim();
    }

    /** A resolved reply carries no pendingAction, so the orchestrator clears the route-lock. */
    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }
}
