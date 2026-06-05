package dev.fedorov.ailife.contracts.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Both directions for one person: outgoing = edges where this person is the
 * subject ("Maria likes books"); incoming = edges where this person is the
 * object ("Vlad gave-gift-to Maria").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonRelationsResponse(
        UUID personId,
        List<RelationDto> outgoing,
        List<RelationDto> incoming) {
}
