package dev.fedorov.ailife.memory.service;

import dev.fedorov.ailife.contracts.memory.PersonRelationsResponse;
import dev.fedorov.ailife.contracts.memory.RelationDto;
import dev.fedorov.ailife.contracts.memory.WriteRelationRequest;
import dev.fedorov.ailife.memory.domain.RelationRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RelationService {

    private final RelationRepository repo;

    public RelationService(RelationRepository repo) {
        this.repo = repo;
    }

    public RelationDto write(WriteRelationRequest req) {
        validate(req);
        float confidence = req.confidence() == null ? 1.0f : req.confidence();
        return repo.insert(
                req.householdId(),
                req.subjectType(),
                req.subjectId(),
                req.edge(),
                req.objectType(),
                req.objectId(),
                req.objectLabel(),
                confidence,
                req.source() == null ? "unknown" : req.source(),
                req.metadata());
    }

    public boolean forget(UUID id) {
        return repo.deleteById(id);
    }

    public PersonRelationsResponse personRelations(UUID householdId, UUID personId) {
        if (householdId == null || personId == null) {
            throw new IllegalArgumentException("householdId and personId must both be non-null");
        }
        List<RelationDto> outgoing = repo.outgoingForPerson(householdId, personId);
        List<RelationDto> incoming = repo.incomingForPerson(householdId, personId);
        return new PersonRelationsResponse(personId, outgoing, incoming);
    }

    private static void validate(WriteRelationRequest req) {
        if (req.householdId() == null) throw new IllegalArgumentException("householdId is required");
        if (req.subjectType() == null || req.subjectType().isBlank())
            throw new IllegalArgumentException("subjectType is required");
        if (req.subjectId() == null) throw new IllegalArgumentException("subjectId is required");
        if (req.edge() == null || req.edge().isBlank())
            throw new IllegalArgumentException("edge is required");
        if (req.objectType() == null || req.objectType().isBlank())
            throw new IllegalArgumentException("objectType is required");
        if (req.objectLabel() == null || req.objectLabel().isBlank())
            throw new IllegalArgumentException("objectLabel is required (for display)");
    }
}
