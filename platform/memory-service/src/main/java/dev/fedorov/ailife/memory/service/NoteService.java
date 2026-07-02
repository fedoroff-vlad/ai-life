package dev.fedorov.ailife.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.memory.domain.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The authored-notes tier of the second-brain substrate. CRUD over
 * {@code memory.note} plus the SB-2 <b>semantic seed</b>: on write the note body
 * is embedded into {@code memory.memories} (via {@link MemoryService},
 * {@code source=note}, {@code {kind:note, refId}}) so the note is recallable through
 * the existing {@code /v1/memories/recall}; on delete the seed is forgotten. SB-3
 * will add the {@code [[wiki-links]]} → {@code memory.relations} seed.
 *
 * <p>The seed is <b>best-effort</b>: the note row is the source of truth and is
 * committed first, so an embedding/llm-gateway outage never fails a note write — it
 * just leaves the note un-indexed until the next successful write.
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    static final String DEFAULT_TYPE = "fact";
    static final String DEFAULT_SOURCE = "user";
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    /** memory-service source tag for the recall seed of a note (SB-2). */
    static final String MEMORY_SOURCE = "note";

    private final NoteRepository repo;
    private final MemoryService memory;
    private final ObjectMapper json;

    public NoteService(NoteRepository repo, MemoryService memory, ObjectMapper json) {
        this.repo = repo;
        this.memory = memory;
        this.json = json;
    }

    public NoteDto create(WriteNoteRequest req) {
        validate(req);
        NoteDto dto = repo.insert(
                req.householdId(),
                req.ownerId(),
                req.title().trim(),
                type(req.type()),
                req.tags(),
                source(req.source()),
                req.personId(),
                req.bodyMd(),
                req.frontmatter()).toDto();
        reseed(dto);
        return dto;
    }

    public Optional<NoteDto> update(UUID id, WriteNoteRequest req) {
        validate(req);
        return repo.update(
                id,
                req.title().trim(),
                type(req.type()),
                req.tags(),
                source(req.source()),
                req.personId(),
                req.bodyMd(),
                req.frontmatter())
                .map(row -> {
                    NoteDto dto = row.toDto();
                    reseed(dto);
                    return dto;
                });
    }

    public Optional<NoteDto> get(UUID id) {
        return repo.findById(id).map(row -> row.toDto());
    }

    public List<NoteDto> list(UUID householdId, Integer limit) {
        if (householdId == null) {
            throw new IllegalArgumentException("householdId is required");
        }
        return repo.listByHousehold(householdId, clampLimit(limit)).stream()
                .map(row -> row.toDto())
                .toList();
    }

    public boolean forget(UUID id) {
        boolean deleted = repo.deleteById(id);
        if (deleted) {
            try {
                memory.forgetBySourceRef(MEMORY_SOURCE, id);
            } catch (RuntimeException e) {
                log.warn("note-seed cleanup failed for note {}: {}", id, e.toString());
            }
        }
        return deleted;
    }

    /**
     * Replace this note's recall seed: drop the previous one (if any) and embed the
     * current body. Best-effort — a failure is logged and swallowed so the note
     * write still succeeds (the row is already committed).
     */
    private void reseed(NoteDto note) {
        try {
            memory.forgetBySourceRef(MEMORY_SOURCE, note.id());
            memory.write(new WriteMemoryRequest(
                    note.householdId(),
                    note.ownerId(),       // note owner scopes the memory (null = household-shared)
                    note.personId(),
                    MEMORY_SOURCE,
                    seedText(note),
                    seedMetadata(note)));
        } catch (RuntimeException e) {
            log.warn("note-seed failed for note {}: {}", note.id(), e.toString());
        }
    }

    /** The corpus we embed: the title plus the body (body carries the substance; title adds signal). */
    private static String seedText(NoteDto note) {
        String body = note.bodyMd();
        return (body != null && !body.isBlank()) ? note.title() + "\n\n" + body : note.title();
    }

    /** {@code {kind, refId, type}} back-pointer so a recall hit resolves to its note row (SB-4 finder). */
    private ObjectNode seedMetadata(NoteDto note) {
        ObjectNode md = json.createObjectNode();
        md.put("kind", "note");
        md.put("refId", note.id().toString());
        if (note.type() != null) {
            md.put("type", note.type());
        }
        return md;
    }

    private static void validate(WriteNoteRequest req) {
        if (req.householdId() == null) {
            throw new IllegalArgumentException("householdId is required");
        }
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
    }

    private static String type(String type) {
        return (type == null || type.isBlank()) ? DEFAULT_TYPE : type.trim();
    }

    private static String source(String source) {
        return (source == null || source.isBlank()) ? DEFAULT_SOURCE : source.trim();
    }

    private static int clampLimit(Integer limit) {
        int effective = (limit == null || limit <= 0) ? DEFAULT_LIMIT : limit;
        return Math.min(effective, MAX_LIMIT);
    }
}
