package dev.fedorov.ailife.agents.notes.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Phrasing;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the reactive {@code note-edit} intent skill (road-test #486, Track H.2 — the notes per-domain
 * <b>edit</b> hole): fix / correct / rename a saved note by just chatting ("исправь заметку про отпуск:
 * едем в Крым", "переименуй заметку про врача в …"), behind a <b>confirm-before-change</b> gate. When
 * {@code NotesIntentRouter} classifies a message as an edit request, it dispatches to {@link #edit}; the
 * confirming reply routes back through {@code ResumeController} to {@link #resume}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this
 * class is the notes-edit adapter and the runner's first non-calendar update consumer. The LLM picks the
 * target note <b>and</b> extracts the new title/body the user gave — threaded through the {@code pendingAction}
 * like a calendar move's new time. A picked note with no stated change re-asks ({@link #missing}); the
 * terminal {@link #act} re-reads the note, applies the change, and PUTs it back (memory-service
 * {@code PUT /v1/notes/{id}} replaces the mutable fields, so the untouched fields are preserved).
 */
@Component
public class NoteEditor
        implements TargetedActionFlow<NoteDto>, CandidateView<NoteDto>, Phrasing<NoteDto> {

    public static final String SKILL_NAME = "note-edit";
    /** pendingAction discriminator the notes ResumeController dispatches on. */
    public static final String FLOW = "note-edit-confirm";
    private static final int MAX_CANDIDATES = 40;
    private static final int SNIPPET_LEN = 100;

    private final NoteClient notes;
    private final PickConfirmActRunner<NoteDto> runner;

    public NoteEditor(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                      NoteClient notes, ObjectMapper json) {
        this.notes = notes;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the household's recent notes, let the LLM pick + extract the change, and reply with a confirm. */
    public Mono<IntentResponse> edit(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative applies the stashed change; anything else leaves it. */
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
        return "noteId";
    }

    @Override
    public String labelField() {
        return "title";
    }

    @Override
    public Set<String> extraAffirmatives() {
        return Set.of("исправь", "исправить", "сохрани", "сохранить", "save");
    }

    @Override
    public Mono<List<NoteDto>> candidates(NormalizedMessage msg) {
        return notes.list(msg.householdId(), MAX_CANDIDATES).map(NoteEditor::editable);
    }

    @Override
    public CandidateView<NoteDto> view() {
        return this;
    }

    @Override
    public Phrasing<NoteDto> phrasing() {
        return this;
    }

    /** Picked a note but the user did not say what to change → ask, without a lock. */
    @Override
    public Optional<String> missing(NoteDto target, JsonNode pick) {
        return hasChange(pick)
                ? Optional.empty()
                : Optional.of("Как исправить заметку «" + safeTitle(target)
                        + "»? Напишите новый текст или заголовок.");
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode pending) {
        String newTitle = text(pending, "newTitle");
        String newBody = text(pending, "newBody");
        return notes.get(targetId)
                .flatMap(note -> notes.update(targetId, new WriteNoteRequest(
                        note.householdId(), note.ownerId(),
                        newTitle != null ? newTitle : note.title(),
                        note.type(), note.tags(), note.source(), note.personId(),
                        newBody != null ? newBody : note.bodyMd(),
                        note.frontmatter())))
                .then();
    }

    /** List notes are maintained through the list-manager (LI-a), not free-text edited here. */
    private static List<NoteDto> editable(List<NoteDto> all) {
        if (all == null) {
            return List.of();
        }
        List<NoteDto> out = new ArrayList<>();
        for (NoteDto n : all) {
            if (!"list".equalsIgnoreCase(n.type())) {
                out.add(n);
            }
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return out;
    }

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(NoteDto n) {
        return n.id();
    }

    @Override
    public String label(NoteDto n) {
        return safeTitle(n);
    }

    @Override
    public void describe(ObjectNode node, NoteDto n) {
        node.put("title", safeTitle(n));
        if (n.type() != null) {
            node.put("type", n.type());
        }
        String snippet = snippet(n.bodyMd());
        if (snippet != null) {
            node.put("snippet", snippet);
        }
    }

    // ----- Phrasing ---------------------------------------------------------------------------------

    @Override
    public String askWhich() {
        return "Какую заметку исправить?";
    }

    @Override
    public String noHousehold() {
        return "Не знаю, к какому хозяйству относится запрос.";
    }

    @Override
    public String emptyPool() {
        return "Не нашёл заметок, которые можно исправить.";
    }

    @Override
    public String noMatch() {
        return "Не нашёл такую заметку. Уточните, что исправить.";
    }

    @Override
    public String readFailed() {
        return "Не смог найти заметку для правки. Попробуйте ещё раз позже.";
    }

    @Override
    public String notReady() {
        return "Нечего исправлять — повторите запрос, пожалуйста.";
    }

    @Override
    public String ambiguous(List<NoteDto> picks) {
        StringBuilder sb = new StringBuilder("Нашёл несколько подходящих заметок — какую исправить?");
        for (NoteDto n : picks) {
            sb.append("\n• «").append(safeTitle(n)).append("»");
        }
        return sb.toString();
    }

    @Override
    public String confirm(NoteDto target, JsonNode pick) {
        String newTitle = text(pick, "newTitle");
        StringBuilder sb = new StringBuilder("Исправить заметку «").append(safeTitle(target)).append("»");
        if (newTitle != null) {
            sb.append(" → «").append(newTitle).append("»");
        }
        if (text(pick, "newBody") != null) {
            sb.append(" (новый текст)");
        }
        return sb.append("? Ответьте «да», чтобы сохранить.").toString();
    }

    @Override
    public String declined(JsonNode pending) {
        return "Оставил «" + title(pending) + "» без изменений.";
    }

    @Override
    public String done(JsonNode pending) {
        return "Исправил заметку «" + title(pending) + "».";
    }

    @Override
    public String actFailed(JsonNode pending) {
        return "Не смог исправить «" + title(pending) + "» — возможно, заметка уже удалена.";
    }

    private static boolean hasChange(JsonNode pick) {
        return text(pick, "newTitle") != null || text(pick, "newBody") != null;
    }

    /** A present, non-blank string field, else null. */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asString().strip();
        return v.isEmpty() ? null : v;
    }

    private static String title(JsonNode pending) {
        return pending.path("title").asString("заметку");
    }

    private static String snippet(String bodyMd) {
        if (bodyMd == null || bodyMd.isBlank()) {
            return null;
        }
        String flat = bodyMd.strip().replaceAll("\\s+", " ");
        return flat.length() > SNIPPET_LEN ? flat.substring(0, SNIPPET_LEN) + "…" : flat;
    }

    private static String safeTitle(NoteDto n) {
        return (n.title() != null && !n.title().isBlank()) ? n.title() : "заметка";
    }
}
