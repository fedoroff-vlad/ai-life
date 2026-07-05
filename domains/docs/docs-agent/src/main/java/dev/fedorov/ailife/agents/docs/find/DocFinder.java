package dev.fedorov.ailife.agents.docs.find;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.docs.config.DocsAgentProperties;
import dev.fedorov.ailife.agents.docs.http.DocumentClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answers "find my X" over the document archive (D-d).
 *
 * <p>Pipeline: one llm-gateway {@code DEFAULT} turn with the {@code doc-finder} SKILL distills the
 * user's request into a short search {@code query} plus an optional {@code docType} filter (strict
 * JSON) → two searches run in parallel: {@code mcp-docs}'s {@code GET /internal/documents/search}
 * (household-scoped trigram) and a {@code memory-service} semantic recall (SB-5 — fuzzy "find my X"
 * that the literal trigram match misses) over the second-brain: a note recall hit is fetched and its
 * {@code frontmatter} {@code {kind:document, refId}} back-pointer resolves to the document row → the
 * merged, de-duplicated hits are listed (title / type / date / party) each with an
 * open link to the stored blob. A blank query (the model couldn't distil one) falls back to the raw
 * user text so a plain "договор аренды" still searches. Every stage soft-fails to a friendly message.
 */
@Component
public class DocFinder {

    private static final Logger log = LoggerFactory.getLogger(DocFinder.class);
    private static final String SKILL_NAME = "doc-finder";
    private static final int LIMIT = 5;
    /** memory-service tags the recall seed of a note carries (SB-2); a note hit is {@code source=note, kind=note}. */
    private static final String NOTE_MEMORY_SOURCE = "note";
    private static final String NOTE_KIND = "note";
    /** The {@code frontmatter.kind} a doc-archiver note carries, so we keep only document notes. */
    private static final String DOCUMENT_KIND = "document";

    private final DocumentClient documents;
    private final MemoryClient memory;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final String publicMediaBaseUrl;

    public DocFinder(DocumentClient documents, MemoryClient memory, LlmClient llm, SkillRegistry skills,
                     AgentManifest manifest, ObjectMapper json, DocsAgentProperties props) {
        this.documents = documents;
        this.memory = memory;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.publicMediaBaseUrl = stripTrailingSlash(props.getPublicMediaBaseUrl());
    }

    public Mono<IntentResponse> find(NormalizedMessage msg) {
        // temperature=0: distilling the query must be faithful, not creative.
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(msg.text() == null ? "" : msg.text())), 0.0);
        return llm.chat(request)
                .flatMap(r -> runSearch(msg, r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("doc find failed: {}", e.toString());
                    return Mono.just(reply("Не удалось выполнить поиск по архиву. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> runSearch(NormalizedMessage msg, String llmContent, String model) {
        JsonNode draft = parseDraft(llmContent);
        String query = draft == null ? null : text(draft, "query");
        if (query == null || query.isBlank()) {
            query = msg.text();   // fall back to the raw request when the model gave no query
        }
        if (query == null || query.isBlank()) {
            return Mono.just(reply("Что искать в архиве? Напишите, например: «найди договор аренды».", model));
        }
        String docType = draft == null ? null : text(draft, "docType");
        String q = query.trim();
        // Trigram (literal) and semantic (meaning) searches run in parallel; each soft-fails to an
        // empty list so one source being down still returns whatever the other found.
        Mono<List<DocumentDto>> trigram = documents.search(msg.householdId(), q, docType, LIMIT)
                .onErrorResume(e -> {
                    log.warn("search_documents failed: {}", e.toString());
                    return Mono.just(List.of());
                });
        Mono<List<DocumentDto>> semantic = semanticHits(msg.householdId(), q, docType);
        return Mono.zip(trigram, semantic)
                .map(t -> reply(formatHits(merge(t.getT1(), t.getT2())), model));
    }

    /**
     * Fuzzy recall via memory-service over the second-brain (SB-5): embed the query, take the note hits,
     * fetch each note and resolve its {@code frontmatter}'s {@code {kind:document, refId}} back-pointer to
     * the document row (doc-archiver now seeds a note, not a raw memory — a recall hit is {@code
     * source=note, kind=note} pointing at the note, whose frontmatter points at the document). Notes that
     * are not document notes are skipped. Soft-fails to an empty list — semantic recall is a bonus on top
     * of the trigram search, never a hard dependency.
     */
    private Mono<List<DocumentDto>> semanticHits(UUID householdId, String query, String docType) {
        return memory.recall(householdId, null, null, query)   // household-scoped, matching the trigram search
                .flatMapMany(Flux::fromIterable)
                .map(this::noteId)
                .filter(id -> id != null)
                .distinct()
                .flatMap(noteId -> memory.getNote(noteId).flatMap(note -> {
                    UUID docId = documentRef(note);
                    if (docId == null) {
                        return Mono.<DocumentDto>empty();   // not a document note — skip
                    }
                    return documents.get(docId).onErrorResume(e -> {
                        log.warn("resolve semantic hit doc {} failed: {}", docId, e.toString());
                        return Mono.empty();
                    });
                }))
                .filter(d -> docType == null || docType.equalsIgnoreCase(d.docType()))
                .collectList()
                .onErrorReturn(List.of());
    }

    /** A recall hit's note id: only {@code source=note}, {@code kind=note} memories carry the {@code refId}. */
    private UUID noteId(RecallMemoryHit hit) {
        if (hit == null || hit.memory() == null || !NOTE_MEMORY_SOURCE.equals(hit.memory().source())) {
            return null;
        }
        JsonNode meta = hit.memory().metadata();
        if (meta == null || !NOTE_KIND.equals(text(meta, "kind"))) {
            return null;
        }
        return parseUuid(text(meta, "refId"));
    }

    /** The document id a doc-archiver note back-points at ({@code frontmatter.{kind:document, refId}}); null otherwise. */
    private UUID documentRef(NoteDto note) {
        if (note == null || note.frontmatter() == null
                || !DOCUMENT_KIND.equals(text(note.frontmatter(), "kind"))) {
            return null;
        }
        return parseUuid(text(note.frontmatter(), "refId"));
    }

    private static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Trigram hits first (relevance-ranked), then any semantic-only extras, de-duplicated by id, capped. */
    private static List<DocumentDto> merge(List<DocumentDto> trigram, List<DocumentDto> semantic) {
        Map<UUID, DocumentDto> byId = new LinkedHashMap<>();
        for (DocumentDto d : trigram) {
            if (d != null && d.id() != null) byId.putIfAbsent(d.id(), d);
        }
        for (DocumentDto d : semantic) {
            if (d != null && d.id() != null) byId.putIfAbsent(d.id(), d);
        }
        List<DocumentDto> merged = new ArrayList<>(byId.values());
        return merged.size() > LIMIT ? merged.subList(0, LIMIT) : merged;
    }

    private String formatHits(List<DocumentDto> hits) {
        if (hits == null || hits.isEmpty()) {
            return "Ничего не нашёл в архиве по этому запросу. Попробуйте другие слова или пришлите документ, если его ещё нет.";
        }
        StringBuilder sb = new StringBuilder("Нашёл в архиве:");
        for (DocumentDto d : hits) {
            sb.append("\n• ").append(line(d));
        }
        return sb.toString();
    }

    private String line(DocumentDto d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.title() != null && !d.title().isBlank() ? d.title() : "документ");
        StringBuilder meta = new StringBuilder();
        appendMeta(meta, docTypeLabel(d.docType()));
        appendMeta(meta, d.party());
        appendMeta(meta, d.docDate() == null ? null : d.docDate().toString());
        if (meta.length() > 0) {
            sb.append(" (").append(meta).append(")");
        }
        if (d.mediaId() != null && !d.mediaId().isBlank()) {
            sb.append(" — ").append(publicMediaBaseUrl).append("/v1/media/").append(d.mediaId());
        }
        return sb.toString();
    }

    private static void appendMeta(StringBuilder meta, String part) {
        if (part == null || part.isBlank()) return;
        if (meta.length() > 0) meta.append(", ");
        meta.append(part);
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "doc-finder SKILL.md not loaded — check skills-classpath"));
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

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return v.isBlank() ? null : v;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
