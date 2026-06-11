package dev.fedorov.ailife.media.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcTemplate over {@code media.media_object}. No ORM features are needed (no joins, no
 * laziness) so JdbcTemplate beats JPA configuration — same call made in memory-service.
 */
@Repository
public class MediaRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<MediaRow> rowMapper;

    public MediaRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> {
            Object metaObj = rs.getObject("metadata");
            JsonNode metadata;
            try {
                metadata = metaObj == null ? json.createObjectNode() : json.readTree(metaObj.toString());
            } catch (JsonProcessingException e) {
                throw new SQLException("invalid metadata json in row " + rs.getObject("id"), e);
            }
            return new MediaRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("household_id", UUID.class),
                    rs.getObject("owner_id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("mime_type"),
                    rs.getLong("size_bytes"),
                    rs.getString("sha256"),
                    rs.getString("bucket"),
                    rs.getString("storage_key"),
                    rs.getString("source"),
                    metadata,
                    rs.getTimestamp("created_at").toInstant());
        };
    }

    public MediaRow insert(UUID id,
                           UUID householdId,
                           UUID ownerId,
                           String kind,
                           String mimeType,
                           long sizeBytes,
                           String sha256,
                           String bucket,
                           String storageKey,
                           String source) {
        jdbc.update("""
                INSERT INTO media.media_object
                    (id, household_id, owner_id, kind, mime_type, size_bytes, sha256,
                     bucket, storage_key, source)
                VALUES
                    (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, householdId, ownerId, kind, mimeType, sizeBytes, sha256,
                bucket, storageKey, source);
        return findById(id).orElseThrow(
                () -> new IllegalStateException("insert succeeded but row not found: " + id));
    }

    public Optional<MediaRow> findById(UUID id) {
        try {
            MediaRow row = jdbc.queryForObject("""
                    SELECT id, household_id, owner_id, kind, mime_type, size_bytes, sha256,
                           bucket, storage_key, source, metadata, created_at
                      FROM media.media_object
                     WHERE id = ?
                    """, rowMapper, id);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean deleteById(UUID id) {
        return jdbc.update("DELETE FROM media.media_object WHERE id = ?", id) > 0;
    }
}
