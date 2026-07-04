package dev.fedorov.ailife.memory.service;

import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import dev.fedorov.ailife.contracts.memory.WriteRelationRequest;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.memory.capture.CaptureOutcome;
import dev.fedorov.ailife.memory.capture.ExtractedRelation;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import dev.fedorov.ailife.memory.capture.NoteCandidate;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
import dev.fedorov.ailife.memory.capture.RelationExtractor;
import dev.fedorov.ailife.memory.config.MemoryServiceProperties;
import dev.fedorov.ailife.memory.http.ProfileClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * memory-from-chat (Stage 4): turn a piece of dialogue into stored memories and
 * graph edges.
 *
 * <p>Three outputs from one message:
 * <ul>
 *   <li>Free-text <b>memories</b> via {@link FactExtractor} → {@link MemoryService}
 *       (the primary fuel; the {@code POST /v1/capture} response).</li>
 *   <li>Structured <b>relations</b> via {@link RelationExtractor} → person resolution
 *       → {@link RelationService} (the graph half, MFC-c).</li>
 *   <li>Curated <b>ambient notes</b> via {@link NoteWorthinessExtractor} → {@link NoteService}
 *       (the high-signal note tier, AC-2 — flag-gated, off by default).</li>
 * </ul>
 *
 * Both the relation and note outputs are best-effort <b>side effects</b>: they never throw and never
 * block the memory write or the triggering bus message. An edge/note whose subject cannot be anchored on
 * a real entity (no speaker for a "self" edge, or an unresolved person name) is still handled sanely — we
 * do not auto-create people from chat (an unresolved name stays a dangling {@code [[link]]}).
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    /** Provenance tag for memories/relations learned from dialogue (vs explicit writes). */
    static final String CAPTURE_SOURCE = "chat-capture";

    /** Provenance for an ambiently-captured explicit-fixation note — user-authored, just not via notes-agent. */
    static final String NOTE_SOURCE_USER = "user";

    /** memory-service source tag a note's recall seed carries (SB-2); the AC-3 dedup filters recall to it. */
    private static final String NOTE_MEMORY_SOURCE = "note";

    /** Top-k for the AC-3 dedup recall — a handful of nearest note-neighbours is enough to spot a duplicate. */
    private static final int DEDUP_RECALL_K = 5;

    /** Subject sentinel the extractor uses for a statement about the speaker. */
    private static final String SELF = "self";

    private final FactExtractor extractor;
    private final RelationExtractor relationExtractor;
    private final NoteWorthinessExtractor noteExtractor;
    private final MemoryService memories;
    private final RelationService relations;
    private final NoteService notes;
    private final ProfileClient profile;
    private final MemoryServiceProperties props;

    public CaptureService(FactExtractor extractor,
                          RelationExtractor relationExtractor,
                          NoteWorthinessExtractor noteExtractor,
                          MemoryService memories,
                          RelationService relations,
                          NoteService notes,
                          ProfileClient profile,
                          MemoryServiceProperties props) {
        this.extractor = extractor;
        this.relationExtractor = relationExtractor;
        this.noteExtractor = noteExtractor;
        this.memories = memories;
        this.relations = relations;
        this.notes = notes;
        this.profile = profile;
        this.props = props;
    }

    public List<MemoryDto> capture(CaptureRequest req) {
        if (req.householdId() == null) {
            throw new IllegalArgumentException("householdId is required");
        }
        if (req.text() == null || req.text().isBlank()) {
            throw new IllegalArgumentException("text must be non-blank");
        }
        List<MemoryDto> written = new ArrayList<>();
        for (String fact : extractor.extract(req.text())) {
            written.add(memories.write(new WriteMemoryRequest(
                    req.householdId(), req.userId(), req.personId(),
                    CAPTURE_SOURCE, fact, null)));
        }
        captureRelations(req);
        captureNotes(req);
        return written;
    }

    /** Best-effort graph extraction — never throws, never blocks the memory write. */
    private void captureRelations(CaptureRequest req) {
        try {
            int written = 0;
            for (ExtractedRelation rel : relationExtractor.extract(req.text())) {
                if (writeRelation(req, rel)) {
                    written++;
                }
            }
            if (written > 0) {
                log.debug("captured {} relation(s) from message", written);
            }
        } catch (Exception e) {
            log.warn("relation capture failed: {}", e.toString());
        }
    }

    /** Resolve the subject to a concrete entity and write the edge. Returns false when skipped. */
    private boolean writeRelation(CaptureRequest req, ExtractedRelation rel) {
        String subjectType;
        UUID subjectId;
        if (SELF.equalsIgnoreCase(rel.subject())) {
            if (req.userId() == null) {
                log.debug("skipping self-relation '{}' — no speaker (userId) on the message", rel.edge());
                return false;
            }
            subjectType = "user";
            subjectId = req.userId();
        } else {
            UUID personId = profile.resolvePersonId(req.householdId(), rel.subject());
            if (personId == null) {
                log.debug("skipping relation — unresolved subject '{}'", rel.subject());
                return false;
            }
            subjectType = "person";
            subjectId = personId;
        }
        relations.write(new WriteRelationRequest(
                req.householdId(), subjectType, subjectId, rel.edge(),
                "label", null, rel.object(), null, CAPTURE_SOURCE, null));
        return true;
    }

    /**
     * Ambient note capture (AC-2) — best-effort, flag-gated, never throws. Only <b>explicit-fixation</b>
     * candidates are written now (the user asked); important-but-inferred facts wait for the approval flow
     * (AC-4) and trivial ones are ignored.
     */
    private void captureNotes(CaptureRequest req) {
        if (!props.getAmbientCapture().isEnabled()) {
            return;
        }
        try {
            int written = 0;
            for (NoteCandidate candidate : noteExtractor.extract(req.text())) {
                if (candidate.outcome() == CaptureOutcome.EXPLICIT_FIXATION && writeNote(req, candidate)) {
                    written++;
                }
            }
            if (written > 0) {
                log.debug("captured {} ambient note(s) from message", written);
            }
        } catch (Exception e) {
            log.warn("ambient note capture failed: {}", e.toString());
        }
    }

    /**
     * Write one explicit-fixation candidate as a curated note (auto-seeds recall + graph via
     * {@link NoteService}). Attribution: a {@code "self"} candidate is owner-scoped; a named subject is
     * resolved to a {@code core.people} UUID (best-effort) and gets a {@code [[name]]} link appended so the
     * note→person edge projects — an unresolved name stays a dangling link, the note is still saved. Before
     * writing, {@link #isDuplicate} (AC-3) skips a near-identical existing note. Returns false when skipped
     * (blank title or near-duplicate).
     */
    private boolean writeNote(CaptureRequest req, NoteCandidate candidate) {
        if (candidate.title() == null || candidate.title().isBlank()) {
            log.debug("skipping ambient note — blank title");
            return false;
        }
        UUID personId = null;
        String body = candidate.body();
        if (!candidate.isSelf() && candidate.subject() != null) {
            personId = profile.resolvePersonId(req.householdId(), candidate.subject());
            body = appendWikiLink(body, candidate.subject());
        }
        if (isDuplicate(req, personId, candidate.title(), body)) {
            log.debug("skipping ambient note '{}' — near-duplicate of an existing note", candidate.title());
            return false;
        }
        notes.create(new WriteNoteRequest(
                req.householdId(),
                req.userId(),            // owner-scoped: the capturing user owns the note
                candidate.title(),
                candidate.type(),        // NoteService defaults a blank type to "fact"
                null,                    // tags — the extractor doesn't produce them
                NOTE_SOURCE_USER,
                personId,
                body,
                null));                  // frontmatter
        return true;
    }

    /**
     * AC-3 dedup — is a near-identical note already stored? Recall the {@code source=note} neighbours in
     * the same scope (matching how {@link NoteService} seeds them: query = {@code title + body}) and compare
     * the nearest cosine distance to {@code memory.ambient-capture.dedup-distance}. Best-effort and
     * <b>fail-open</b>: a recall blip yields "not a duplicate" so a lookup failure never silently drops a note.
     */
    private boolean isDuplicate(CaptureRequest req, UUID personId, String title, String body) {
        try {
            String query = (body == null || body.isBlank()) ? title : title + "\n\n" + body;
            List<RecallMemoryHit> hits = memories.recall(new RecallMemoryRequest(
                    req.householdId(), req.userId(), personId, query, DEDUP_RECALL_K));
            double nearest = hits.stream()
                    .filter(h -> h.memory() != null && NOTE_MEMORY_SOURCE.equals(h.memory().source()))
                    .mapToDouble(RecallMemoryHit::distance)
                    .min()
                    .orElse(Double.MAX_VALUE);
            return nearest < props.getAmbientCapture().getDedupDistance();
        } catch (Exception e) {
            log.warn("ambient note dedup check failed, writing anyway: {}", e.toString());
            return false;
        }
    }

    /** Append a {@code [[name]]} wiki-link to a body so SB-3 projects a note→person edge (idempotent). */
    private static String appendWikiLink(String body, String name) {
        String link = "[[" + name.trim() + "]]";
        if (body == null || body.isBlank()) {
            return link;
        }
        return body.contains(link) ? body : body + "\n" + link;
    }
}
