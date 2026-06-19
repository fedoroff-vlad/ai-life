package dev.fedorov.ailife.memory.service;

import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * memory-from-chat (Stage 4): turn a piece of dialogue into stored memories.
 * Runs the LLM {@link FactExtractor} over the message, then writes each extracted
 * fact via {@link MemoryService} (embed + insert) with the request's scope.
 * Best-effort — nothing durable extracted → nothing written (empty result).
 */
@Service
public class CaptureService {

    /** Provenance tag for memories learned from dialogue (vs explicit writes). */
    static final String CAPTURE_SOURCE = "chat-capture";

    private final FactExtractor extractor;
    private final MemoryService memories;

    public CaptureService(FactExtractor extractor, MemoryService memories) {
        this.extractor = extractor;
        this.memories = memories;
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
        return written;
    }
}
