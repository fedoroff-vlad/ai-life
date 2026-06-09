package dev.fedorov.ailife.memory.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.memory.RelationDto;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcTemplate over memory.relations. Single-hop edges only — multi-hop graph
 * traversal would warrant Apache AGE, which is deferred (see 005 changelog
 * comment for why).
 */
@Repository
public class RelationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<RelationDto> rowMapper;

    public RelationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> {
            Object metaObj = rs.getObject("metadata");
            JsonNode metadata;
            try {
                metadata = metaObj == null
                        ? json.createObjectNode()
                        : json.readTree(metaObj.toString());
            } catch (JsonProcessingException e) {
                throw new SQLException("invalid metadata json in row " + rs.getObject("id"), e);
            }
            return new RelationDto(
                    rs.getObject("id", UUID.class),
                    rs.getObject("household_id", UUID.class),
                    rs.getString("subject_type"),
                    rs.getObject("subject_id", UUID.class),
                    rs.getString("edge"),
                    rs.getString("object_type"),
                    rs.getObject("object_id", UUID.class),
                    rs.getString("object_label"),
                    rs.getFloat("confidence"),
                    rs.getString("source"),
                    metadata,
                    rs.getTimestamp("created_at").toInstant());
        };
    }

    public RelationDto insert(UUID householdId,
                              String subjectType,
                              UUID subjectId,
                              String edge,
                              String objectType,
                              UUID objectId,
                              String objectLabel,
                              float confidence,
                              String source,
                              JsonNode metadata) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memory.relations
                    (id, household_id, subject_type, subject_id, edge,
                     object_type, object_id, object_label, confidence, source, metadata)
                VALUES
                    (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                id, householdId, subjectType, subjectId, edge,
                objectType, objectId, objectLabel, confidence, source,
                metadata == null || metadata.isNull() ? "{}" : metadata.toString());
        return findById(id).orElseThrow(
                () -> new IllegalStateException("insert succeeded but row not found: " + id));
    }

    public Optional<RelationDto> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT id, household_id, subject_type, subject_id, edge,
                           object_type, object_id, object_label, confidence, source,
                           metadata, created_at
                      FROM memory.relations
                     WHERE id = ?
                    """, rowMapper, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean deleteById(UUID id) {
        return jdbc.update("DELETE FROM memory.relations WHERE id = ?", id) > 0;
    }

    /** Edges where the person is the subject. Newest first. */
    public List<RelationDto> outgoingForPerson(UUID householdId, UUID personId) {
        return jdbc.query("""
                SELECT id, household_id, subject_type, subject_id, edge,
                       object_type, object_id, object_label, confidence, source,
                       metadata, created_at
                  FROM memory.relations
                 WHERE household_id = ?
                   AND subject_type = 'person'
                   AND subject_id = ?
                 ORDER BY created_at DESC
                """, rowMapper, householdId, personId);
    }

    /** Edges where the person is the object. Newest first. */
    public List<RelationDto> incomingForPerson(UUID householdId, UUID personId) {
        return jdbc.query("""
                SELECT id, household_id, subject_type, subject_id, edge,
                       object_type, object_id, object_label, confidence, source,
                       metadata, created_at
                  FROM memory.relations
                 WHERE household_id = ?
                   AND object_type = 'person'
                   AND object_id = ?
                 ORDER BY created_at DESC
                """, rowMapper, householdId, personId);
    }
}
