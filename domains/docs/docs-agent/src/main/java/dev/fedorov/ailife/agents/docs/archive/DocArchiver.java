package dev.fedorov.ailife.agents.docs.archive;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.docs.http.DocumentClient;
import dev.fedorov.ailife.agents.docs.http.OcrClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.docs.SaveDocumentInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.sharing.SharingConfirm;
import dev.fedorov.ailife.sharing.SharingContext;
import dev.fedorov.ailife.sharing.SharingResolution;
import dev.fedorov.ailife.sharing.SharingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns an inbound document photo into an archived {@code docs.document} row (D-c ingest).
 *
 * <p>Pipeline: OCR the image via the shared {@code mcp-media-processing} capability (its
 * {@code POST /internal/ocr} passthrough) → one llm-gateway {@code DEFAULT} turn with the
 * {@code doc-archiver} SKILL as the system prompt extracts the metadata JSON (doc_type / title /
 * party / date / amount / currency / tags) from the OCR text + the user's caption hint → archive it
 * via {@code mcp-docs}'s {@code POST /internal/documents}, storing the full OCR text as the search
 * corpus → reply confirming what was filed.
 *
 * <p>Archiving is non-destructive (no confirm-before-write, unlike a finance receipt): we save and
 * report. Empty OCR still archives the blob with whatever metadata the model could infer from the
 * caption, so nothing is silently dropped. Every stage soft-fails to a friendly message.
 */
@Component
public class DocArchiver {

    private static final Logger log = LoggerFactory.getLogger(DocArchiver.class);
    private static final String SKILL_NAME = "doc-archiver";
    /** Cap the OCR text we store/prompt with so a huge multi-page scan can't blow up the row/turn. */
    private static final int MAX_OCR_CHARS = 20_000;
    /** {@code source} of the second-brain note an archived document seeds (SB-5 universal write seam). */
    private static final String NOTE_SOURCE = "docs-agent";
    /** A document is reference material — the second-brain note {@code type} (see the note manifest). */
    private static final String NOTE_TYPE = "reference";

    private final OcrClient ocr;
    private final DocumentClient documents;
    private final MemoryClient memory;
    private final LlmClient llm;
    private final SharingResolver sharing;
    private final SharingConfirm sharingConfirm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public DocArchiver(OcrClient ocr, DocumentClient documents, MemoryClient memory, LlmClient llm,
                       SharingResolver sharing, SharingConfirm sharingConfirm, SkillRegistry skills,
                       AgentManifest manifest, ObjectMapper json) {
        this.ocr = ocr;
        this.documents = documents;
        this.memory = memory;
        this.llm = llm;
        this.sharing = sharing;
        this.sharingConfirm = sharingConfirm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> archive(NormalizedMessage msg, String mediaId) {
        return ocr.ocr(mediaId)
                .map(r -> clip(r.text()))
                .flatMap(text -> extractAndSave(msg, mediaId, text))
                .onErrorResume(e -> {
                    log.warn("doc archive failed for media {}: {}", mediaId, e.toString());
                    return Mono.just(reply(
                            "Не удалось обработать документ. Пришлите фото почётче или попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> extractAndSave(NormalizedMessage msg, String mediaId, String ocrText) {
        // temperature=0: metadata extraction must be faithful to the document, not creative.
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(extractionInput(ocrText, msg.text()))), 0.0);
        return llm.chat(request).flatMap(r -> {
            JsonNode draft = parseDraft(r.content());
            // ADR-0002 slice 7 (sharing write path): a document is routed to the acting member's shared
            // vs personal household deterministically by the shared SharingResolver + DocsSharingPolicy
            // (warranty/contract → the family's shared household, else private; a shared choice with no
            // family household degrades to personal). The routing/fallback rules live once in libs/sharing;
            // any profile hiccup degrades to the envelope household so a document never fails to save on it.
            SharingContext ctx = new SharingContext(List.of(), false, text(draft, "docType"));
            return sharing.resolve(msg.userId(), null, ctx, msg.householdId())
                    // A routing hiccup must never lose a document → degrade to the envelope household.
                    .onErrorResume(e -> {
                        log.debug("document household routing failed, using envelope household: {}", e.toString());
                        return Mono.just(new SharingResolution.Resolved(msg.householdId()));
                    })
                    .flatMap(res -> switch (res) {
                        case SharingResolution.Resolved rr ->
                                persist(rr.household() != null ? rr.household() : msg.householdId(),
                                        msg.userId(), mediaId, ocrText, draft)
                                        // why-trace (#485/G2): a successful archive is a write — a payload-free
                                        // "what I did" line for a later "почему ты так сделал" answer.
                                        .map(saved -> reply(successText(saved.docType(), saved.title()), r.model())
                                                .withTrace("wrote: archived a document"))
                                        .onErrorResume(e -> {
                                            log.warn("save_document failed for media {}: {}", mediaId, e.toString());
                                            return Mono.just(reply(
                                                    "Не смог сохранить документ в архив. Попробуйте позже.", null));
                                        });
                        // Genuinely ambiguous doc type (other/unreadable) → defer + ask "личное или общее?"
                        // instead of silently filing a privacy boundary (item 8, DS-N).
                        case SharingResolution.NeedsConfirm nc ->
                                Mono.just(askScope(nc, msg.userId(), mediaId, ocrText, draft, r.model()));
                    });
        });
    }

    /**
     * Save the document + seed its second-brain note, returning the saved row. Shared by the resolved archive
     * path and the DS-N {@link #finishArchive} resume path so the persist logic lives once.
     *
     * <p>SB-5: seed the archived document into the second-brain as an authored note (memory-service
     * {@code /v1/notes}) rather than a raw memory row. The note auto-seeds recall (SB-2) so doc-finder still
     * surfaces it by meaning, but it lands in the ONE store every agent reads. Soft-fails internally — the
     * document is already saved + text-searchable, so a memory-service outage is harmless.
     */
    private Mono<DocumentDto> persist(UUID household, UUID ownerId, String mediaId, String ocrText, JsonNode draft) {
        SaveDocumentInput input = buildInput(household, ownerId, mediaId, ocrText, draft);
        return documents.save(input)
                .flatMap(saved -> memory.note(buildNote(saved, ocrText)).thenReturn(saved));
    }

    /**
     * Build the deferred "личное или общее?" ask (item 8, DS-N), stashing everything {@link #finishArchive}
     * needs to file the document once the owner answers — the sender, the blob id, the OCR corpus, and the
     * extracted metadata draft — so the confirm neither re-OCRs nor re-extracts.
     */
    private IntentResponse askScope(SharingResolution.NeedsConfirm nc, UUID ownerId, String mediaId,
                                    String ocrText, JsonNode draft, String model) {
        ObjectNode stash = json.createObjectNode();
        if (ownerId != null) stash.put("ownerId", ownerId.toString());
        if (mediaId != null) stash.put("mediaId", mediaId);
        if (ocrText != null) stash.put("ocrText", ocrText);
        if (draft != null) stash.set("draft", draft);
        String label = docLabelForAsk(draft);
        return new IntentResponse(manifest.name(), sharingConfirm.question(label), model,
                sharingConfirm.pendingAction(nc, stash));
    }

    /**
     * The {@link SharingConfirm.Finish} callback (hit from the docs {@code ResumeController}): the owner
     * answered личное/общее, the resolver already picked + recorded the household — now file the stashed
     * document into it (+ seed its note). Returns the confirmation text.
     */
    public Mono<String> finishArchive(UUID household, SharingScope chosen, JsonNode stash) {
        UUID ownerId = stash != null && stash.hasNonNull("ownerId")
                ? UUID.fromString(stash.get("ownerId").asString()) : null;
        String mediaId = stash != null ? text(stash, "mediaId") : null;
        String ocrText = stash != null ? text(stash, "ocrText") : null;
        JsonNode draft = stash != null ? stash.get("draft") : null;
        return persist(household, ownerId, mediaId, ocrText, draft)
                .map(saved -> successText(saved.docType(), saved.title()))
                .onErrorResume(e -> {
                    log.warn("save_document (resume) failed for media {}: {}", mediaId, e.toString());
                    return Mono.just("Не смог сохранить документ в архив. Попробуйте позже.");
                });
    }

    /** The label the DS-N ask names the document by: its extracted title, else the doc-type label, else generic. */
    private static String docLabelForAsk(JsonNode draft) {
        String title = text(draft, "title");
        if (title != null && !title.isBlank()) {
            return "«" + title + "»";
        }
        String label = docTypeLabel(text(draft, "docType"));
        return label != null ? "«" + label + "»" : "этот документ";
    }

    /**
     * The second-brain note an archived document seeds (SB-5). The body is the OCR corpus (so recall +
     * a future markdown export carry the text); the {@code frontmatter} holds a {@code {kind:document,
     * refId}} back-pointer so a note recall hit resolves back to the {@code docs.document} row that owns
     * the blob + structured fields (doc-finder reads it).
     */
    private WriteNoteRequest buildNote(DocumentDto saved, String ocrText) {
        return new WriteNoteRequest(
                saved.householdId(),
                saved.ownerId(),               // seeded under the sender; recall/search stay household-scoped
                noteTitle(saved),
                NOTE_TYPE,
                noteTags(saved),
                NOTE_SOURCE,
                null,                          // personId — a document is not a person note
                indexText(saved, ocrText),     // body = the OCR corpus (recall + export)
                noteFrontmatter(saved));       // {kind:document, refId, …} back-pointer
    }

    /** memory.note requires a non-blank title; fall back to the doc-type label, then a generic word. */
    private static String noteTitle(DocumentDto saved) {
        if (saved.title() != null && !saved.title().isBlank()) {
            return saved.title();
        }
        String label = docTypeLabel(saved.docType());
        return label != null ? label : "документ";
    }

    /** Coarse tags for the note: always {@code document}, plus the doc-type when known. */
    private static List<String> noteTags(DocumentDto saved) {
        List<String> tags = new ArrayList<>();
        tags.add("document");
        if (saved.docType() != null && !saved.docType().isBlank()) {
            tags.add(saved.docType().trim().toLowerCase());
        }
        return tags;
    }

    /** The corpus we embed: the full OCR text, falling back to the title so a text-less scan is still findable. */
    private static String indexText(DocumentDto saved, String ocrText) {
        String text = blankToNull(ocrText);
        return text != null ? text : saved.title();
    }

    /** A {@code {kind, refId}} back-pointer (+ light metadata) so a note recall hit resolves to its document row. */
    private ObjectNode noteFrontmatter(DocumentDto saved) {
        ObjectNode fm = json.createObjectNode();
        fm.put("kind", "document");
        fm.put("refId", saved.id().toString());
        if (saved.docType() != null) fm.put("docType", saved.docType());
        if (saved.title() != null) fm.put("title", saved.title());
        return fm;
    }

    private SaveDocumentInput buildInput(UUID householdId, UUID ownerId, String mediaId,
                                         String ocrText, JsonNode draft) {
        return new SaveDocumentInput(
                householdId,                        // resolved shared vs personal household (ADR-0002 slice 7)
                ownerId,                            // archived under the sender; search is household-scoped
                mediaId,
                text(draft, "docType"),
                text(draft, "title"),
                text(draft, "party"),
                parseDate(text(draft, "docDate")),
                decimal(draft, "amount"),
                text(draft, "currency"),
                blankToNull(ocrText),
                array(draft, "tags"));
    }

    private String extractionInput(String ocrText, String userText) {
        String caption = blankToNull(userText);
        StringBuilder sb = new StringBuilder();
        if (caption != null) {
            sb.append("User note: ").append(caption).append("\n\n");
        }
        sb.append("Document text (OCR):\n").append(ocrText == null ? "" : ocrText);
        return sb.toString();
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "doc-archiver SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private JsonNode parseDraft(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String successText(String docType, String title) {
        String what = (title != null && !title.isBlank()) ? title : "документ";
        String kind = docTypeLabel(docType);
        return "Заархивировал: " + what + (kind == null ? "" : " (" + kind + ")")
                + ". Найду по запросу «найди мой …».";
    }

    private static String docTypeLabel(String docType) {
        if (docType == null) return null;
        return switch (docType.trim().toLowerCase()) {
            case "receipt" -> "чек";
            case "contract" -> "договор";
            case "warranty" -> "гарантия";
            case "note" -> "справка/заметка";
            default -> null;
        };
    }

    private static String clip(String text) {
        if (text == null) return null;
        return text.length() > MAX_OCR_CHARS ? text.substring(0, MAX_OCR_CHARS) : text;
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? blankToNull(node.get(field).asString()) : null;
    }

    private static JsonNode array(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && node.get(field).isArray() ? node.get(field) : null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        JsonNode v = node.get(field);
        try {
            return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            return LocalDate.parse(date.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
