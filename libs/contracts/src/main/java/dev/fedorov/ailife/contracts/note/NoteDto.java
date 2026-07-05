package dev.fedorov.ailife.contracts.note;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One authored note in the "second brain" substrate (epic #257) — mirrors a
 * {@code memory.note} row. The durable, human-readable unit of the knowledge base;
 * markdown is its interchange form, this row is the source of truth.
 *
 * <p>Scoped {@code (householdId, ownerId)} — a null {@code ownerId} is
 * household-shared. {@code id} (not {@code title}) is the stable anchor that links /
 * {@code refId} / graph edges point at. {@code type} is the coarse class
 * ({@code person|fact|idea|reference|journal|goal|reflection}); {@code tags} are
 * free-form labels; {@code source} records who authored it ({@code user} or an agent
 * name); {@code personId} is set when the note is about a specific person.
 * {@code bodyMd} is the CommonMark body (may carry {@code [[wiki-links]]} and
 * {@code #tags}); {@code frontmatter} is an open jsonb bag for extensibility.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NoteDto(
        UUID id,
        UUID householdId,
        UUID ownerId,
        String title,
        String type,
        List<String> tags,
        String source,
        UUID personId,
        String bodyMd,
        JsonNode frontmatter,
        Instant createdAt,
        Instant updatedAt) {
}
