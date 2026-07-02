package dev.fedorov.ailife.agents.docs.find;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Answers "find my X" over the document archive (D-d).
 *
 * <p>Pipeline: one llm-gateway {@code DEFAULT} turn with the {@code doc-finder} SKILL distills the
 * user's request into a short search {@code query} plus an optional {@code docType} filter (strict
 * JSON) → {@code mcp-docs}'s {@code GET /internal/documents/search} runs the household-scoped trigram
 * search → the reply lists the matches (title / type / date / party) each with an open link to the
 * stored blob. A blank query (the model couldn't distil one) falls back to the raw user text so a
 * plain "договор аренды" still searches. Every stage soft-fails to a friendly message.
 */
@Component
public class DocFinder {

    private static final Logger log = LoggerFactory.getLogger(DocFinder.class);
    private static final String SKILL_NAME = "doc-finder";
    private static final int LIMIT = 5;

    private final DocumentClient documents;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final String publicMediaBaseUrl;

    public DocFinder(DocumentClient documents, LlmClient llm, SkillRegistry skills,
                     AgentManifest manifest, ObjectMapper json, DocsAgentProperties props) {
        this.documents = documents;
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
        return documents.search(msg.householdId(), query.trim(), docType, LIMIT)
                .map(hits -> reply(formatHits(hits), model))
                .onErrorResume(e -> {
                    log.warn("search_documents failed: {}", e.toString());
                    return Mono.just(reply("Не удалось выполнить поиск по архиву. Попробуйте позже.", null));
                });
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
