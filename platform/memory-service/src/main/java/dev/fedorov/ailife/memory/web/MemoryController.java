package dev.fedorov.ailife.memory.web;

import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import dev.fedorov.ailife.memory.service.MemoryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(path = "/v1/memories", produces = MediaType.APPLICATION_JSON_VALUE)
public class MemoryController {

    private final MemoryService service;

    public MemoryController(MemoryService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MemoryDto> write(@RequestBody WriteMemoryRequest req) {
        return ResponseEntity.ok(service.write(req));
    }

    /**
     * Flat, most-recent-first list of stored facts for the memory-review digest (MQ-1, #488).
     * recall enumerates by similarity; this enumerates by recency so the owner can audit + prune.
     * {@code userId}/{@code personId} narrow the scope (mirroring recall).
     */
    @GetMapping
    public ResponseEntity<List<MemoryDto>> list(
            @RequestParam("householdId") UUID householdId,
            @RequestParam(value = "userId", required = false) UUID userId,
            @RequestParam(value = "personId", required = false) UUID personId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(service.list(householdId, userId, personId, limit));
    }

    /**
     * A single stored fact by id (or 404) — the read behind MQ-2's fact "correct" (road-test #488):
     * the notes-agent re-reads the row to recover its household/user before forget-then-writing the
     * corrected fact under the same scope.
     */
    @GetMapping("/{id}")
    public ResponseEntity<MemoryDto> get(@PathVariable UUID id) {
        return service.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(path = "/recall", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<RecallMemoryHit>> recall(@RequestBody RecallMemoryRequest req) {
        return ResponseEntity.ok(service.recall(req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> forget(@PathVariable UUID id) {
        return service.forget(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
