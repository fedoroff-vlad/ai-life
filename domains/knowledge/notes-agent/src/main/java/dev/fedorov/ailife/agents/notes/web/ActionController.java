package dev.fedorov.ailife.agents.notes.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.web.AgentActionController;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Inter-agent action endpoint for notes (Stage 4 / Track C1 envelope). The orchestrator forwards an
 * {@link AgentActionRequest} here.
 *
 * <p>Registers the reserved <b>{@code undo}</b> action (road-test #486, Track H — the notes rollout): when
 * the owner says "отмени последнее" right after a "запомни …" capture, the orchestrator dispatches the note's
 * stored undo handle here, and this reverses it by deleting the just-captured note via memory-service's
 * {@code DELETE /v1/notes/{id}} ({@link NoteClient#delete}, which also drops its recall seed + wiki-link
 * edges). Returns the agent's own user-facing confirmation ({@code {message}}) naming the deleted note; a
 * missing/already-deleted note → an honest {@code ok=false} the orchestrator surfaces verbatim (never a
 * silent no-op). Mirrors tasks-agent's {@code ActionController} (H3a).
 */
@RestController
public class ActionController extends AgentActionController {

    private final NoteClient notes;
    private final ObjectMapper json;

    public ActionController(NoteClient notes, ObjectMapper json) {
        super("notes");
        this.notes = notes;
        this.json = json;
        register("undo", this::undo);
    }

    @PostMapping("/agents/notes/actions/{action}")
    public Mono<AgentActionResult> action(@PathVariable String action,
                                          @RequestBody AgentActionRequest request) {
        return dispatch(action, request);
    }

    /** Reverse a captured note: delete it by the stored id and confirm with its title. */
    private Mono<AgentActionResult> undo(AgentActionRequest request) {
        UUID noteId = uuidArg(request, "noteId");
        if (noteId == null) {
            return Mono.just(AgentActionResult.error("undo requires args.noteId"));
        }
        String title = stringArg(request, "title");
        return notes.delete(noteId)
                .then(Mono.fromSupplier(() -> {
                    ObjectNode node = json.createObjectNode();
                    node.put("message", "Удалил заметку"
                            + (title != null ? ": «" + title + "»" : "") + ".");
                    return AgentActionResult.ok(node);
                }))
                .onErrorResume(e -> Mono.just(AgentActionResult.error(
                        "Не нашёл заметку для отмены — возможно, она уже удалена.")));
    }

    private static UUID uuidArg(AgentActionRequest request, String field) {
        JsonNode args = request.args();
        if (args == null) {
            return null;
        }
        JsonNode v = args.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(v.asString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringArg(AgentActionRequest request, String field) {
        JsonNode args = request.args();
        if (args == null) {
            return null;
        }
        JsonNode v = args.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }
}
