package dev.fedorov.ailife.mcp.docs.tools;

import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.docs.SaveDocumentInput;
import dev.fedorov.ailife.mcp.docs.domain.DocumentEntity;
import dev.fedorov.ailife.mcp.docs.domain.DocumentRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Docs domain opener (D-a): source-of-truth store + search over docs.* (the personal document
 * archive). Intentionally low-level — it persists a document (blob id + extracted metadata + OCR
 * text) and searches the text; the OCR call and the metadata extraction live in docs-agent's
 * doc-archiver skill, not here.
 *
 * Scope rule: every tool takes a householdId and reads/writes only within that household (mirrors
 * mcp-creator / mcp-briefing). Per-person attribution is the optional ownerId (null = household-shared).
 */
@Component
public class DocsMcpTools {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final DocumentRepository documents;

    public DocsMcpTools(DocumentRepository documents) {
        this.documents = documents;
    }

    @Tool(description = """
            Archive a document. `householdId` and `mediaId` (the media-service object id of the stored
            photo/scan) are required; a null `ownerId` is household-shared. Each call stores a NEW
            document (append-only). `docType` is a coarse class (receipt|contract|warranty|note|other);
            `title`/`party` (merchant or counterparty)/`docDate` are the extracted metadata;
            `amount`+`currency` only for money documents; `ocrText` is the full recognised text (the
            search corpus); `tags` is a free-form JSON array of labels. Returns the stored document.
            """)
    @Transactional
    public DocumentDto saveDocument(SaveDocumentInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.mediaId(), "mediaId");
        DocumentEntity doc = new DocumentEntity(
                UUID.randomUUID(), input.householdId(), input.ownerId(), input.mediaId());
        doc.setDocType(input.docType());
        doc.setTitle(input.title());
        doc.setParty(input.party());
        doc.setDocDate(input.docDate());
        doc.setAmount(input.amount());
        doc.setCurrency(input.currency());
        doc.setOcrText(input.ocrText());
        doc.setTags(input.tags());
        return documents.save(doc).toDto();
    }

    @Tool(description = """
            Get one archived document by its id. Returns null if there is no such document.
            """)
    @Transactional(readOnly = true)
    public DocumentDto getDocument(UUID id) {
        requireField(id, "id");
        return documents.findById(id).map(DocumentEntity::toDto).orElse(null);
    }

    @Tool(description = """
            List the most recent documents in a household, newest first, optionally narrowed to one
            `docType` (receipt|contract|warranty|note|other). `limit` caps the result (default 20,
            max 100). Returns an empty list when the household has none.
            """)
    @Transactional(readOnly = true)
    public List<DocumentDto> listDocuments(UUID householdId, String docType, Integer limit) {
        requireField(householdId, "householdId");
        return documents.listRecent(householdId, blankToNull(docType), clampLimit(limit))
                .stream().map(DocumentEntity::toDto).toList();
    }

    @Tool(description = """
            Search a household's documents by free text over their title, party and OCR text
            (case-insensitive). Optionally narrow to one `docType`. `limit` caps the result (default
            20, max 100). Results are ranked by relevance to the query, then recency. Returns an empty
            list when nothing matches. This is the cheap text match; semantic "find my X" recall is
            added on top in the agent.
            """)
    @Transactional(readOnly = true)
    public List<DocumentDto> searchDocuments(UUID householdId, String query, String docType, Integer limit) {
        requireField(householdId, "householdId");
        requireField(query, "query");
        return documents.search(householdId, query.trim(), blankToNull(docType), clampLimit(limit))
                .stream().map(DocumentEntity::toDto).toList();
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
