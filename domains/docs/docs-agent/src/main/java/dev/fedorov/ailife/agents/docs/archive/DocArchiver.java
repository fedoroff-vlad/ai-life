package dev.fedorov.ailife.agents.docs.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.docs.http.DocumentClient;
import dev.fedorov.ailife.agents.docs.http.OcrClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.docs.SaveDocumentInput;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    private final OcrClient ocr;
    private final DocumentClient documents;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public DocArchiver(OcrClient ocr, DocumentClient documents, LlmClient llm,
                       SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.ocr = ocr;
        this.documents = documents;
        this.llm = llm;
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
            SaveDocumentInput input = buildInput(msg, mediaId, ocrText, draft);
            return documents.save(input)
                    .map(saved -> reply(successText(saved.docType(), saved.title()), r.model()))
                    .onErrorResume(e -> {
                        log.warn("save_document failed for media {}: {}", mediaId, e.toString());
                        return Mono.just(reply("Не смог сохранить документ в архив. Попробуйте позже.", null));
                    });
        });
    }

    private SaveDocumentInput buildInput(NormalizedMessage msg, String mediaId, String ocrText, JsonNode draft) {
        return new SaveDocumentInput(
                msg.householdId(),
                msg.userId(),                       // archived under the sender; search is household-scoped
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
        return node != null && node.hasNonNull(field) ? blankToNull(node.get(field).asText()) : null;
    }

    private static JsonNode array(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) && node.get(field).isArray() ? node.get(field) : null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        JsonNode v = node.get(field);
        try {
            return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText().trim());
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
