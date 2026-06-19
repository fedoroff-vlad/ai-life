package dev.fedorov.ailife.memory.capture;

/**
 * One structured edge pulled out of a message by {@link RelationExtractor}:
 * {@code subject —edge→ object}. Labels are raw, as the message phrases them —
 * person resolution (label → {@code core.people} UUID) and the write into
 * {@code memory.relations} happen in a later slice. The literal subject
 * {@code "self"} marks a statement about the speaker (e.g. "я люблю джаз").
 */
public record ExtractedRelation(String subject, String edge, String object) {
}
