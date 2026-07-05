package dev.fedorov.ailife.contracts.docs;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Archive a document. {@code householdId} + {@code mediaId} are required (a document is always a
 * stored blob); a null {@code ownerId} is household-shared. Each call inserts a new row — documents
 * are append-only, not upserted (unlike the per-person profile stores). The docs-agent's
 * {@code doc-archiver} skill fills the metadata ({@code docType}/{@code title}/{@code party}/
 * {@code docDate}/{@code amount}/{@code currency}) from the OCR text + the user's caption; {@code
 * ocrText} is the full recognised text used as the search corpus; {@code tags} is a free-form JSON
 * array of labels.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveDocumentInput(
        UUID householdId,
        UUID ownerId,
        String mediaId,
        String docType,
        String title,
        String party,
        LocalDate docDate,
        BigDecimal amount,
        String currency,
        String ocrText,
        JsonNode tags) {
}
