package dev.fedorov.ailife.memory.service;

import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import dev.fedorov.ailife.memory.config.MemoryServiceProperties;
import dev.fedorov.ailife.memory.domain.MemoryRepository;
import dev.fedorov.ailife.memory.domain.MemoryRow;
import dev.fedorov.ailife.memory.embed.EmbeddingClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class MemoryService {

    private final MemoryRepository repo;
    private final EmbeddingClient embed;
    private final MemoryServiceProperties props;

    public MemoryService(MemoryRepository repo, EmbeddingClient embed, MemoryServiceProperties props) {
        this.repo = repo;
        this.embed = embed;
        this.props = props;
    }

    public MemoryDto write(WriteMemoryRequest req) {
        validateText(req.text());
        float[] embedding = embed.embed(req.text());
        guardDim(embedding);
        MemoryRow row = repo.insert(
                req.householdId(),
                req.userId(),
                req.personId(),
                req.source() == null ? "unknown" : req.source(),
                req.text(),
                req.metadata(),
                embedding);
        return row.toDto();
    }

    public List<RecallMemoryHit> recall(RecallMemoryRequest req) {
        validateText(req.query());
        int k = clampK(req.k());
        float[] embedding = embed.embed(req.query());
        guardDim(embedding);
        return repo.recall(req, embedding, k);
    }

    public boolean forget(UUID id) {
        return repo.deleteById(id);
    }

    private int clampK(Integer k) {
        int effective = (k == null || k <= 0) ? props.getDefaultK() : k;
        return Math.min(effective, props.getMaxK());
    }

    private static void validateText(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("text/query must be non-blank");
        }
    }

    private void guardDim(float[] v) {
        if (v.length != props.getDim()) {
            throw new IllegalStateException(
                    "embedding dim mismatch: expected " + props.getDim() + ", got " + v.length
                            + ". Set memory.dim to match the active llm-gateway provider, or migrate the column.");
        }
    }
}
