package dev.fedorov.ailife.memory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.RecallMemoryRequest;
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
 * JdbcTemplate over memory.memories. JPA was tried-and-skipped here — the
 * embedding column is pgvector(384), which JPA mapping handles only via a
 * custom Hibernate type, and we don't need any of the ORM features (no joins,
 * no laziness, no cascades). One file beats the configuration.
 */
@Repository
public class MemoryRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<MemoryRow> rowMapper;

    public MemoryRepository(JdbcTemplate jdbc, ObjectMapper json) {
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
            return new MemoryRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("household_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("person_id", UUID.class),
                    rs.getString("source"),
                    rs.getString("text"),
                    metadata,
                    rs.getTimestamp("created_at").toInstant());
        };
    }

    /**
     * Insert and return the new row. We construct the UUID in Java so the caller
     * sees it without a {@code RETURNING} round-trip dance.
     */
    public MemoryRow insert(UUID householdId,
                            UUID userId,
                            UUID personId,
                            String source,
                            String text,
                            JsonNode metadata,
                            float[] embedding) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memory.memories
                    (id, household_id, user_id, person_id, source, text, metadata, embedding)
                VALUES
                    (?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
                """,
                id,
                householdId,
                userId,
                personId,
                source,
                text,
                metadataToString(metadata),
                Vectors.toLiteral(embedding));
        return findById(id).orElseThrow(
                () -> new IllegalStateException("insert succeeded but row not found: " + id));
    }

    public Optional<MemoryRow> findById(UUID id) {
        try {
            MemoryRow row = jdbc.queryForObject("""
                    SELECT id, household_id, user_id, person_id, source, text, metadata, created_at
                      FROM memory.memories
                     WHERE id = ?
                    """, rowMapper, id);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean deleteById(UUID id) {
        return jdbc.update("DELETE FROM memory.memories WHERE id = ?", id) > 0;
    }

    /**
     * Top-k recall by cosine distance (smaller = more similar). Scope filter:
     * household required; user and person narrow further when set in the request.
     * Returns hits ordered by ascending distance.
     */
    public List<RecallMemoryHit> recall(RecallMemoryRequest req, float[] queryEmbedding, int k) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, household_id, user_id, person_id, source, text, metadata, created_at,
                       (embedding <=> ?::vector) AS distance
                  FROM memory.memories
                 WHERE household_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(Vectors.toLiteral(queryEmbedding));
        args.add(req.householdId());

        if (req.userId() != null) {
            sql.append("   AND (user_id IS NULL OR user_id = ?)\n");
            args.add(req.userId());
        }
        if (req.personId() != null) {
            sql.append("   AND (person_id = ? OR person_id IS NULL)\n");
            args.add(req.personId());
        }
        sql.append(" ORDER BY distance ASC\n LIMIT ?");
        args.add(k);

        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            MemoryRow row = rowMapper.mapRow(rs, rowNum);
            double distance = rs.getDouble("distance");
            return new RecallMemoryHit(row.toDto(), distance);
        }, args.toArray());
    }

    private String metadataToString(JsonNode metadata) {
        if (metadata == null || metadata.isNull()) {
            return "{}";
        }
        return metadata.toString();
    }

}
