package dev.fedorov.ailife.contracts.docs;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One archived document — mirrors a {@code docs.document} row. Scoped {@code (householdId, ownerId)}
 * (a null {@code ownerId} is household-shared). {@code mediaId} is the media-service object id of the
 * stored blob (the photo/scan; we never re-store bytes). {@code docType} is a coarse class
 * ({@code receipt|contract|warranty|note|other}); {@code title}/{@code party} (merchant or
 * counterparty)/{@code docDate} are the extracted metadata; {@code amount}+{@code currency} are set
 * only for money documents (receipts/invoices). {@code ocrText} is the full recognised text (the
 * search corpus); {@code tags} is a free-form JSON array of labels. Absent fields stay null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentDto(
        UUID id,
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
        JsonNode tags,
        Instant createdAt) {
}
