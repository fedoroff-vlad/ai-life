package dev.fedorov.ailife.contracts.note;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * Create or replace a {@code memory.note} (second-brain substrate, epic #257). Used
 * by {@code POST /v1/notes} (create) and {@code PUT /v1/notes/{id}} (replace the
 * mutable fields). {@code householdId} + {@code title} are required; a null
 * {@code ownerId} is household-shared; a blank {@code type} defaults to {@code fact}
 * and a null {@code source} to {@code user} server-side. {@code tags}/{@code bodyMd}/
 * {@code frontmatter}/{@code personId} are optional.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WriteNoteRequest(
        UUID householdId,
        UUID ownerId,
        String title,
        String type,
        List<String> tags,
        String source,
        UUID personId,
        String bodyMd,
        JsonNode frontmatter) {
}
