package dev.fedorov.ailife.mcp.docs.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    /**
     * Most-recent documents in a household, optionally narrowed to one {@code docType}. Native SQL
     * with explicit CAST: pgjdbc cannot infer the type of a NULL bound parameter inside an
     * {@code IS NULL} comparison (same workaround the profile stores use for their nullable filters).
     */
    @Query(value = """
            SELECT * FROM docs.document d
            WHERE d.household_id = :householdId
              AND (CAST(:docType AS text) IS NULL OR d.doc_type = CAST(:docType AS text))
            ORDER BY d.doc_date DESC NULLS LAST, d.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentEntity> listRecent(@Param("householdId") UUID householdId,
                                    @Param("docType") String docType,
                                    @Param("limit") int limit);

    /**
     * Trigram/substring search over the document's title + party + OCR text in a household, optionally
     * narrowed to one {@code docType}. Case-insensitive {@code ILIKE} accelerated by the
     * {@code gin_trgm_ops} index on the same concatenated expression (080-docs). Ranked by trigram
     * similarity to the query, then recency.
     */
    @Query(value = """
            SELECT * FROM docs.document d
            WHERE d.household_id = :householdId
              AND (CAST(:docType AS text) IS NULL OR d.doc_type = CAST(:docType AS text))
              AND (coalesce(d.title, '') || ' ' || coalesce(d.party, '') || ' ' || coalesce(d.ocr_text, ''))
                  ILIKE '%' || :query || '%'
            ORDER BY similarity(
                       coalesce(d.title, '') || ' ' || coalesce(d.party, '') || ' ' || coalesce(d.ocr_text, ''),
                       :query) DESC,
                     d.doc_date DESC NULLS LAST, d.created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<DocumentEntity> search(@Param("householdId") UUID householdId,
                                @Param("query") String query,
                                @Param("docType") String docType,
                                @Param("limit") int limit);
}
