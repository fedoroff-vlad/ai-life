package dev.fedorov.ailife.memory.web;

import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
import dev.fedorov.ailife.contracts.memory.WriteMemoryRequest;
import dev.fedorov.ailife.memory.service.MemoryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
