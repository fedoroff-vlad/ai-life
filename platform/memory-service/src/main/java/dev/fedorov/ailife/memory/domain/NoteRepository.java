package dev.fedorov.ailife.memory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcTemplate over {@code memory.note} (SB-1). Mirrors {@link MemoryRepository} /
 * {@link RelationRepository}: no JPA, {@code jsonb} handled as text via the shared
 * {@link ObjectMapper}. {@code tags} and {@code frontmatter} are jsonb — tags as a
 * JSON array of strings, frontmatter as an open object.
 */
@Repository
public class NoteRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<NoteRow> rowMapper;

    public NoteRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new NoteRow(
                rs.getObject("id", UUID.class),
                rs.getObject("household_id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getString("title"),
                rs.getString("type"),
                readTags(rs.getObject("tags"), rs.getObject("id")),
                rs.getString("source"),
                rs.getObject("person_id", UUID.class),
                rs.getString("body_md"),
                readJson(rs.getObject("frontmatter"), rs.getObject("id")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static final String COLUMNS =
            "id, household_id, owner_id, title, type, tags, source, person_id, body_md, "
                    + "frontmatter, created_at, updated_at";

    public NoteRow insert(UUID householdId, UUID ownerId, String title, String type,
                          List<String> tags, String source, UUID personId, String bodyMd,
                          JsonNode frontmatter) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memory.note
                    (id, household_id, owner_id, title, type, tags, source, person_id, body_md, frontmatter)
                VALUES
                    (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb)
                """,
                id, householdId, ownerId, title, type,
                tagsToJson(tags), source, personId, bodyMd, jsonToString(frontmatter));
        return findById(id).orElseThrow(
                () -> new IllegalStateException("insert succeeded but row not found: " + id));
    }

    /** Replace the mutable fields; {@code created_at} stays, {@code updated_at} bumps. */
    public Optional<NoteRow> update(UUID id, String title, String type, List<String> tags,
                                    String source, UUID personId, String bodyMd, JsonNode frontmatter) {
        int rows = jdbc.update("""
                UPDATE memory.note
                   SET title = ?, type = ?, tags = ?::jsonb, source = ?, person_id = ?,
                       body_md = ?, frontmatter = ?::jsonb, updated_at = now()
                 WHERE id = ?
                """,
                title, type, tagsToJson(tags), source, personId, bodyMd, jsonToString(frontmatter), id);
        return rows > 0 ? findById(id) : Optional.empty();
    }

    public Optional<NoteRow> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM memory.note WHERE id = ?", rowMapper, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolve a {@code [[wiki-link]]} target to a note id by title within a household
     * (case-insensitive; most-recently-updated wins on duplicate titles). SB-3 link
     * resolution. Empty when no note carries that title.
     */
    public Optional<UUID> findIdByTitle(UUID householdId, String title) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id FROM memory.note
                     WHERE household_id = ? AND lower(title) = lower(?)
                     ORDER BY updated_at DESC
                     LIMIT 1
                    """, UUID.class, householdId, title));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Most-recent notes in a household (by {@code updated_at}). */
    public List<NoteRow> listByHousehold(UUID householdId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM memory.note
                 WHERE household_id = ?
                 ORDER BY updated_at DESC
                 LIMIT ?
                """, rowMapper, householdId, limit);
    }

    /**
     * Every note in a household, uncapped — the SB-7 markdown-vault export. Ordered by title
     * (then {@code created_at}) for a stable, human-browsable vault rather than the recency order
     * the paged {@link #listByHousehold} uses.
     */
    public List<NoteRow> listAllByHousehold(UUID householdId) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM memory.note
                 WHERE household_id = ?
                 ORDER BY lower(title), created_at
                """, rowMapper, householdId);
    }

    /**
     * One random note in the household whose last touch is older than {@code olderThan} — the
     * proactive-resurfacing candidate (a note the owner hasn't revisited in a while). Random so
     * repeated wakes vary; empty when nothing is that stale.
     */
    public Optional<NoteRow> resurfaceCandidate(UUID householdId, java.time.Instant olderThan) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("SELECT " + COLUMNS + """
                     FROM memory.note
                     WHERE household_id = ? AND updated_at < ?
                     ORDER BY random()
                     LIMIT 1
                    """, rowMapper, householdId, java.sql.Timestamp.from(olderThan)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean deleteById(UUID id) {
        return jdbc.update("DELETE FROM memory.note WHERE id = ?", id) > 0;
    }

    private List<String> readTags(Object raw, Object rowId) {
        if (raw == null) {
            return List.of();
        }
        try {
            JsonNode node = json.readTree(raw.toString());
            if (!node.isArray()) {
                return List.of();
            }
            List<String> tags = new ArrayList<>(node.size());
            node.forEach(n -> tags.add(n.asText()));
            return tags;
        } catch (Exception e) {
            throw new RuntimeException("invalid tags json in note " + rowId, e);
        }
    }

    private JsonNode readJson(Object raw, Object rowId) {
        try {
            return raw == null ? json.createObjectNode() : json.readTree(raw.toString());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("invalid frontmatter json in note " + rowId, e);
        }
    }

    private String tagsToJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        try {
            return json.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize tags", e);
        }
    }

    private static String jsonToString(JsonNode node) {
        return node == null || node.isNull() ? "{}" : node.toString();
    }
}
