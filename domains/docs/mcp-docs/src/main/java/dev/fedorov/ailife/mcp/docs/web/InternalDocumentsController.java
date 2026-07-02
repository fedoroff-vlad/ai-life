package dev.fedorov.ailife.mcp.docs.web;

import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.docs.SaveDocumentInput;
import dev.fedorov.ailife.mcp.docs.tools.DocsMcpTools;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-MCP REST passthrough for the document archive. docs-agent (D-c/D-d) has already decided it
 * wants to save or search — it hits these deterministic HTTP paths rather than an LLM-driven MCP
 * tool call (the MCP/SSE binding stays for future selection but isn't MockWebServer-testable). Each
 * endpoint delegates straight to {@link DocsMcpTools} so the tool's scope/validation applies
 * identically. Mirrors mcp-briefing's {@code InternalBriefingProfileController}.
 */
@RestController
@RequestMapping("/internal/documents")
public class InternalDocumentsController {

    private final DocsMcpTools tools;

    public InternalDocumentsController(DocsMcpTools tools) {
        this.tools = tools;
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody SaveDocumentInput input) {
        try {
            return ResponseEntity.ok(tools.saveDocument(input));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Fetch one document by id; 404 when there is no such document. */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentDto> get(@PathVariable UUID id) {
        DocumentDto dto = tools.getDocument(id);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    /** Most-recent documents in a household, optionally narrowed to one docType. */
    @GetMapping
    public List<DocumentDto> list(@RequestParam UUID householdId,
                                  @RequestParam(required = false) String docType,
                                  @RequestParam(required = false) Integer limit) {
        return tools.listDocuments(householdId, docType, limit);
    }

    /** Free-text search over a household's documents (title + party + OCR text). */
    @GetMapping("/search")
    public List<DocumentDto> search(@RequestParam UUID householdId,
                                    @RequestParam String query,
                                    @RequestParam(required = false) String docType,
                                    @RequestParam(required = false) Integer limit) {
        return tools.searchDocuments(householdId, query, docType, limit);
    }
}
