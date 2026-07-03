package dev.fedorov.ailife.contracts.note;

import java.util.List;
import java.util.UUID;

/**
 * The backlinks of a note (second-brain substrate, SB-3, epic #257): the other notes
 * whose body {@code [[wiki-links]]} point at this one. Resolved from
 * {@code memory.relations} note→note edges — the "linked mentions" panel of a
 * knowledge base. {@code noteId} echoes the requested note; {@code backlinks} are the
 * source notes newest-first (empty when nothing links here).
 */
public record NoteBacklinksResponse(
        UUID noteId,
        List<NoteDto> backlinks) {
}
