package dev.fedorov.ailife.memory.service;

import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.memory.domain.NoteRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The authored-notes tier of the second-brain substrate (SB-1). Pure CRUD over
 * {@code memory.note} for now — SB-2 will seed the body into {@code memory.memories}
 * (recall) and SB-3 the {@code [[wiki-links]]} into {@code memory.relations} (graph)
 * on write. Coarse field defaults (blank {@code type} → {@code fact}, null
 * {@code source} → {@code user}) keep the note-format manifest's fields always
 * populated.
 */
@Service
public class NoteService {

    static final String DEFAULT_TYPE = "fact";
    static final String DEFAULT_SOURCE = "user";
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final NoteRepository repo;

    public NoteService(NoteRepository repo) {
        this.repo = repo;
    }

    public NoteDto create(WriteNoteRequest req) {
        validate(req);
        return repo.insert(
                req.householdId(),
                req.ownerId(),
                req.title().trim(),
                type(req.type()),
                req.tags(),
                source(req.source()),
                req.personId(),
                req.bodyMd(),
                req.frontmatter()).toDto();
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
                req.frontmatter()).map(row -> row.toDto());
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
        return repo.deleteById(id);
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
