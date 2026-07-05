package dev.fedorov.ailife.memory.domain;

import tools.jackson.databind.JsonNode;
import dev.fedorov.ailife.contracts.note.NoteDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for one row of {@code memory.note} — the authored-notes tier of the
 * second-brain substrate (SB-1). Plain record over JdbcTemplate, same posture as
 * {@link MemoryRow} / the relations row (no JPA — no joins/laziness/cascades needed).
 */
public record NoteRow(
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

    public NoteDto toDto() {
        return new NoteDto(id, householdId, ownerId, title, type, tags, source,
                personId, bodyMd, frontmatter, createdAt, updatedAt);
    }
}
