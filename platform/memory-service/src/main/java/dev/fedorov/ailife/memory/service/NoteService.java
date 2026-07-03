package dev.fedorov.ailife.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import dev.fedorov.ailife.contracts.memory.WriteRelationRequest;
import dev.fedorov.ailife.contracts.note.NoteBacklinksResponse;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.memory.domain.NoteRepository;
import dev.fedorov.ailife.memory.domain.NoteRow;
import dev.fedorov.ailife.memory.http.ProfileClient;
import dev.fedorov.ailife.memory.note.WikiLinkParser;
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
 * adds a <b>graph seed</b> too: {@code [[wiki-links]]} in the body project into
 * {@code memory.relations} edges (subject = this note; target resolves to a note by
 * title, a person, else a dangling {@code label}) so backlinks come from the existing
 * relation store.
 *
 * <p>Both seeds are <b>best-effort</b>: the note row is the source of truth and is
 * committed first, so an embedding/llm-gateway/graph outage never fails a note write —
 * it just leaves the note un-indexed/un-linked until the next successful write.
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    static final String DEFAULT_TYPE = "fact";
    static final String DEFAULT_SOURCE = "user";
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    /** A note is a resurfacing candidate once it's gone this long without a touch (proactive resurfacing). */
    static final int DEFAULT_RESURFACE_DAYS = 7;
    /** memory-service source tag for the recall seed of a note (SB-2). */
    static final String MEMORY_SOURCE = "note";
    /** Relation source tag + edge/subject types for a note's {@code [[wiki-link]]} edges (SB-3). */
    static final String RELATION_SOURCE = "note";
    static final String LINK_SUBJECT_TYPE = "note";
    static final String LINK_EDGE = "links_to";
    static final String OBJECT_NOTE = "note";
    static final String OBJECT_PERSON = "person";
    static final String OBJECT_LABEL = "label";

    private final NoteRepository repo;
    private final MemoryService memory;
    private final RelationService relations;
    private final ProfileClient profile;
    private final ObjectMapper json;

    public NoteService(NoteRepository repo, MemoryService memory, RelationService relations,
                       ProfileClient profile, ObjectMapper json) {
        this.repo = repo;
        this.memory = memory;
        this.relations = relations;
        this.profile = profile;
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
        reseedLinks(dto);
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
                    reseedLinks(dto);
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

    /**
     * Pick one note worth resurfacing for the household — a random note untouched for at least
     * {@code olderThanDays} (null/≤0 → {@value #DEFAULT_RESURFACE_DAYS}). Empty when nothing is that
     * stale. Backs the proactive-resurfacing wake (the scheduler-driven "полгода назад ты отмечал …").
     */
    public Optional<NoteDto> resurface(UUID householdId, Integer olderThanDays) {
        if (householdId == null) {
            throw new IllegalArgumentException("householdId is required");
        }
        int days = (olderThanDays == null || olderThanDays <= 0) ? DEFAULT_RESURFACE_DAYS : olderThanDays;
        java.time.Instant cutoff = java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
        return repo.resurfaceCandidate(householdId, cutoff).map(NoteRow::toDto);
    }

    public boolean forget(UUID id) {
        Optional<NoteRow> existing = repo.findById(id);
        boolean deleted = repo.deleteById(id);
        if (deleted) {
            try {
                memory.forgetBySourceRef(MEMORY_SOURCE, id);
            } catch (RuntimeException e) {
                log.warn("note-seed cleanup failed for note {}: {}", id, e.toString());
            }
            existing.ifPresent(row -> {
                try {
                    relations.forgetNoteLinks(row.householdId(), id);
                } catch (RuntimeException e) {
                    log.warn("note-link cleanup failed for note {}: {}", id, e.toString());
                }
            });
        }
        return deleted;
    }

    /** The other notes whose {@code [[wiki-links]]} point at this note (SB-3 backlinks). */
    public Optional<NoteBacklinksResponse> backlinks(UUID id) {
        return repo.findById(id).map(row -> {
            List<NoteDto> sources = relations.noteBacklinkIds(row.householdId(), id).stream()
                    .map(repo::findById)
                    .flatMap(Optional::stream)
                    .map(NoteRow::toDto)
                    .toList();
            return new NoteBacklinksResponse(id, sources);
        });
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

    /**
     * Replace this note's {@code [[wiki-link]]} edges: drop the previous ones and
     * project the current body's links into {@code memory.relations}. Each distinct
     * target resolves to a note (by title) or a person, else stays a dangling
     * {@code label} edge. Best-effort — a failure is logged and swallowed so the note
     * write still succeeds (the row is already committed).
     */
    private void reseedLinks(NoteDto note) {
        try {
            relations.forgetNoteLinks(note.householdId(), note.id());
            for (String target : WikiLinkParser.parse(note.bodyMd())) {
                writeLinkEdge(note, target);
            }
        } catch (RuntimeException e) {
            log.warn("note-link seed failed for note {}: {}", note.id(), e.toString());
        }
    }

    /** Resolve one {@code [[target]]} to an edge (note → note/person/label) and write it. */
    private void writeLinkEdge(NoteDto note, String target) {
        Optional<UUID> targetNote = repo.findIdByTitle(note.householdId(), target);
        String objectType;
        UUID objectId;
        if (targetNote.isPresent()) {
            if (targetNote.get().equals(note.id())) {
                return; // a note linking to itself carries no signal — skip.
            }
            objectType = OBJECT_NOTE;
            objectId = targetNote.get();
        } else {
            UUID personId = profile.resolvePersonId(note.householdId(), target);
            if (personId != null) {
                objectType = OBJECT_PERSON;
                objectId = personId;
            } else {
                objectType = OBJECT_LABEL; // dangling link — kept as a labelled stub edge.
                objectId = null;
            }
        }
        relations.write(new WriteRelationRequest(
                note.householdId(), LINK_SUBJECT_TYPE, note.id(), LINK_EDGE,
                objectType, objectId, target, 1.0f, RELATION_SOURCE, null));
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
