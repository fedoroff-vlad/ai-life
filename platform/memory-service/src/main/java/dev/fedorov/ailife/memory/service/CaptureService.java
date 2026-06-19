package dev.fedorov.ailife.memory.service;

import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import dev.fedorov.ailife.contracts.memory.WriteRelationRequest;
import dev.fedorov.ailife.memory.capture.ExtractedRelation;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import dev.fedorov.ailife.memory.capture.RelationExtractor;
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
 * <p>Two outputs from one message:
 * <ul>
 *   <li>Free-text <b>memories</b> via {@link FactExtractor} → {@link MemoryService}
 *       (the primary fuel; the {@code POST /v1/capture} response).</li>
 *   <li>Structured <b>relations</b> via {@link RelationExtractor} → person resolution
 *       → {@link RelationService} (the graph half, MFC-c).</li>
 * </ul>
 *
 * Relation capture is a best-effort <b>side effect</b>: it never throws and never
 * blocks the memory write or the triggering bus message. An edge whose subject
 * cannot be anchored on a real entity (no speaker for a "self" edge, or an
 * unresolved person name) is dropped — we do not auto-create people from chat.
 */
@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

    /** Provenance tag for memories/relations learned from dialogue (vs explicit writes). */
    static final String CAPTURE_SOURCE = "chat-capture";

    /** Subject sentinel the extractor uses for a statement about the speaker. */
    private static final String SELF = "self";

    private final FactExtractor extractor;
    private final RelationExtractor relationExtractor;
    private final MemoryService memories;
    private final RelationService relations;
    private final ProfileClient profile;

    public CaptureService(FactExtractor extractor,
                          RelationExtractor relationExtractor,
                          MemoryService memories,
                          RelationService relations,
                          ProfileClient profile) {
        this.extractor = extractor;
        this.relationExtractor = relationExtractor;
        this.memories = memories;
        this.relations = relations;
        this.profile = profile;
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
}
