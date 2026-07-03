package dev.fedorov.ailife.memory.web;

import dev.fedorov.ailife.contracts.note.NoteBacklinksResponse;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.memory.service.NoteService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CRUD over the authored-notes tier of the second-brain substrate (SB-1, epic #257).
 * On write the body auto-seeds recall (SB-2) and {@code [[wiki-link]]} graph edges
 * (SB-3); {@code GET /{id}/backlinks} reads the notes that link here.
 */
@RestController
@RequestMapping(path = "/v1/notes", produces = MediaType.APPLICATION_JSON_VALUE)
public class NoteController {

    private final NoteService service;

    public NoteController(NoteService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NoteDto> create(@RequestBody WriteNoteRequest req) {
        return ResponseEntity.ok(service.create(req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<NoteDto> get(@PathVariable UUID id) {
        return service.get(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<NoteDto>> list(@RequestParam("householdId") UUID householdId,
                                              @RequestParam(value = "limit", required = false) Integer limit) {
        return ResponseEntity.ok(service.list(householdId, limit));
    }

    @GetMapping("/{id}/backlinks")
    public ResponseEntity<NoteBacklinksResponse> backlinks(@PathVariable UUID id) {
        return service.backlinks(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NoteDto> update(@PathVariable UUID id, @RequestBody WriteNoteRequest req) {
        return service.update(id, req)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> forget(@PathVariable UUID id) {
        return service.forget(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
