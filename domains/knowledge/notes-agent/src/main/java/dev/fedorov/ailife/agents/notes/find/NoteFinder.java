package dev.fedorov.ailife.agents.notes.find;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.note.NoteBacklinksResponse;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Answers "что я думал про …" over the knowledge base (SB-4).
 *
 * <p>Pipeline: one llm-gateway {@code DEFAULT} turn with the {@code note-finder} SKILL distils the
 * request into a short search {@code query} (strict JSON) → a memory-service semantic recall surfaces
 * the matching notes ({@code source=note}, {@code {kind:note, refId}}) whose {@code refId} back-pointers
 * resolve to their note rows → the reply lists them (title + a short snippet); the connected notes of
 * the top hit are appended from its {@code [[wiki-link]]} backlinks (SB-3). A blank query falls back to
 * the raw user text. Every stage soft-fails to a friendly message.
 */
@Component
public class NoteFinder {

    private static final Logger log = LoggerFactory.getLogger(NoteFinder.class);
    private static final String SKILL_NAME = "note-finder";
    private static final int LIMIT = 5;
    /** memory-service source tag a note's recall seed carries (SB-2); must match {@code NoteService}. */
    private static final String MEMORY_SOURCE = "note";
    private static final int SNIPPET_CHARS = 140;

    private final NoteClient notes;
    private final MemoryClient memory;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public NoteFinder(NoteClient notes, MemoryClient memory, LlmClient llm, SkillRegistry skills,
                      AgentManifest manifest, ObjectMapper json) {
        this.notes = notes;
        this.memory = memory;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> find(NormalizedMessage msg) {
        // temperature=0: distilling the query must be faithful, not creative.
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(msg.text() == null ? "" : msg.text())), 0.0);
        return llm.chat(request)
                .flatMap(r -> runRecall(msg, r.content(), r.model()))
                .onErrorResume(e -> {
                    log.warn("note find failed: {}", e.toString());
                    return Mono.just(reply("Не удалось выполнить поиск по заметкам. Попробуйте позже.", null));
                });
    }

    private Mono<IntentResponse> runRecall(NormalizedMessage msg, String llmContent, String model) {
        JsonNode draft = parseDraft(llmContent);
        String query = draft == null ? null : text(draft, "query");
        if (query == null || query.isBlank()) {
            query = msg.text();   // fall back to the raw request when the model gave no query
        }
        if (query == null || query.isBlank()) {
            return Mono.just(reply("Что вспомнить? Напишите, например: «что я думал про …».", model));
        }
        return memory.recall(msg.householdId(), null, null, query.trim())
                .flatMap(hits -> resolveNotes(hits)
                        .flatMap(found -> found.isEmpty()
                                ? Mono.just(reply(nothingFound(), model))
                                : withBacklinks(found, model)));
    }

    /** Resolve the note-scoped recall hits to their note rows, in relevance order, de-duplicated, capped. */
    private Mono<List<NoteDto>> resolveNotes(List<RecallMemoryHit> hits) {
        return Flux.fromIterable(hits)
                .map(this::refId)
                .filter(id -> id != null)
                .distinct()
                .take(LIMIT)
                .flatMapSequential(id -> notes.get(id).onErrorResume(e -> {
                    log.warn("resolve note {} failed: {}", id, e.toString());
                    return Mono.empty();
                }))
                .collectList()
                .onErrorReturn(List.of());
    }

    /** Append the top hit's connected notes (its backlinks) — soft-fails to just the hit list. */
    private Mono<IntentResponse> withBacklinks(List<NoteDto> found, String model) {
        NoteDto top = found.get(0);
        return notes.backlinks(top.id())
                .onErrorResume(e -> {
                    log.warn("backlinks for note {} failed: {}", top.id(), e.toString());
                    return Mono.empty();
                })
                .map(bl -> reply(format(found, bl), model))
                .defaultIfEmpty(reply(format(found, null), model));
    }

    /** A recall hit's note id: only {@code note}-sourced, {@code kind=note} memories carry a {@code refId}. */
    private UUID refId(RecallMemoryHit hit) {
        if (hit == null || hit.memory() == null || !MEMORY_SOURCE.equals(hit.memory().source())) {
            return null;
        }
        JsonNode meta = hit.memory().metadata();
        if (meta == null || !"note".equals(text(meta, "kind"))) {
            return null;
        }
        String ref = text(meta, "refId");
        if (ref == null) {
            return null;
        }
        try {
            return UUID.fromString(ref);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String format(List<NoteDto> found, NoteBacklinksResponse backlinks) {
        StringBuilder sb = new StringBuilder("Вот что нашёл в заметках:");
        for (NoteDto n : found) {
            sb.append("\n• ").append(line(n));
        }
        if (backlinks != null && backlinks.backlinks() != null && !backlinks.backlinks().isEmpty()) {
            sb.append("\n\nСвязано с «").append(found.get(0).title()).append("»:");
            for (NoteDto b : backlinks.backlinks()) {
                sb.append("\n· ").append(b.title() != null && !b.title().isBlank() ? b.title() : "заметка");
            }
        }
        return sb.toString();
    }

    private static String line(NoteDto n) {
        StringBuilder sb = new StringBuilder();
        sb.append(n.title() != null && !n.title().isBlank() ? n.title() : "заметка");
        String snippet = snippet(n.bodyMd());
        if (snippet != null) {
            sb.append(" — ").append(snippet);
        }
        return sb.toString();
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String oneLine = body.strip().replaceAll("\\s+", " ");
        return oneLine.length() > SNIPPET_CHARS
                ? oneLine.substring(0, SNIPPET_CHARS).strip() + "…"
                : oneLine;
    }

    private static String nothingFound() {
        return "Ничего не нашёл в заметках по этому запросу. Попробуйте другие слова или запомните это через «запомни …».";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "note-finder SKILL.md not loaded — check skills-classpath"));
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

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return v.isBlank() ? null : v;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
