package dev.fedorov.ailife.memory.web;

import dev.fedorov.ailife.contracts.memory.PersonRelationsResponse;
import dev.fedorov.ailife.contracts.memory.RelationDto;
import dev.fedorov.ailife.contracts.memory.WriteRelationRequest;
import dev.fedorov.ailife.memory.service.RelationService;
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

import java.util.UUID;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class RelationController {

    private final RelationService service;

    public RelationController(RelationService service) {
        this.service = service;
    }

    @PostMapping(path = "/v1/relations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RelationDto> write(@RequestBody WriteRelationRequest req) {
        return ResponseEntity.ok(service.write(req));
    }

    @DeleteMapping("/v1/relations/{id}")
    public ResponseEntity<Void> forget(@PathVariable UUID id) {
        return service.forget(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/v1/graph/person/{personId}/relations")
    public ResponseEntity<PersonRelationsResponse> personRelations(
            @PathVariable UUID personId,
            @RequestParam("householdId") UUID householdId) {
        return ResponseEntity.ok(service.personRelations(householdId, personId));
    }
}
