package dev.fedorov.ailife.agents.notes.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the reactive {@code note-delete} intent skill (road-test #486, Track H.2 — the notes per-domain
 * delete hole): delete a saved note by just chatting ("удали заметку про X / последнюю заметку"), behind a
 * <b>confirm-before-delete</b> gate. When {@link NotesIntentRouter} classifies a message as a delete
 * request, it dispatches here; the confirming reply routes back through {@code ResumeController}.
 *
 * <p>Two turns over the Stage-4 pending-action lock (mirrors finance's {@code TransactionDeleter} + tasks'
 * {@code TaskDeleter}):
 * <ol>
 *   <li>{@link #delete} — read the household's recent notes ({@link NoteClient#list}), let the LLM
 *       ({@code note-delete} SKILL, temperature 0) pick which candidate they mean, and reply with a
 *       {@code pendingAction} asking to confirm the deletion (route-locks to notes). Nothing is deleted
 *       yet. An ambiguous / unmatched target lists / asks instead of guessing.</li>
 *   <li>{@link #resume} — on the reply: an affirmative deletes the stashed note via
 *       {@link NoteClient#delete} (memory-service {@code DELETE /v1/notes/{id}} — the same reversal the undo
 *       primitive uses, which also drops the recall seed + wiki-link edges); anything else leaves it.</li>
 * </ol>
 * Every stage soft-fails to a friendly Russian message (no pendingAction → no lock).
 */
@Component
public class NoteDeleter {

    private static final Logger log = LoggerFactory.getLogger(NoteDeleter.class);
    public static final String SKILL_NAME = "note-delete";
    /** pendingAction discriminator the notes ResumeController dispatches on. */
    public static final String FLOW = "note-delete-confirm";
    private static final int MAX_CANDIDATES = 40;
    private static final int SNIPPET_LEN = 100;
    private static final Set<String> AFFIRMATIVE = Set.of(
            "да", "ага", "верно", "удали", "удалить", "убери", "убрать", "забудь", "ок", "окей", "давай", "+",
            "yes", "y", "ok", "delete", "confirm");

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final NoteClient notes;
    private final ObjectMapper json;

    public NoteDeleter(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                       NoteClient notes, ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.notes = notes;
        this.json = json;
    }

    public Mono<IntentResponse> delete(NormalizedMessage msg) {
        String userText = msg == null ? null : msg.text();
        if (userText == null || userText.isBlank()) {
            return Mono.just(reply("Какую заметку удалить?", null));
        }
        if (msg.householdId() == null) {
            return Mono.just(reply("Не знаю, к какому хозяйству относится запрос.", null));
        }
        return notes.list(msg.householdId(), MAX_CANDIDATES)
                .flatMap(all -> resolveAndConfirm(userText, all))
                .onErrorResume(e -> {
                    log.warn("note-delete failed: {}", e.toString());
                    return Mono.just(reply("Не смог найти заметку для удаления. Попробуйте ещё раз позже.", null));
                });
    }

    private Mono<IntentResponse> resolveAndConfirm(String userText, List<NoteDto> all) {
        List<NoteDto> candidates = deletable(all);
        if (candidates.isEmpty()) {
            return Mono.just(reply("Не нашёл заметок, которые можно удалить.", null));
        }
        ObjectNode userMsg = json.createObjectNode();
        userMsg.put("userText", userText);
        userMsg.set("candidates", candidateList(candidates));

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(userMsg.toString())), 0.0);

        return llm.chat(req).map(resp -> pickReply(parsePick(resp.content()), candidates, resp.model()));
    }

    private IntentResponse pickReply(Pick pick, List<NoteDto> candidates, String model) {
        if (pick == null || pick.indices().isEmpty()) {
            return reply("Не нашёл такую заметку. Уточните, что удалить.", model);
        }
        if (pick.indices().size() > 1) {
            StringBuilder sb = new StringBuilder("Нашёл несколько подходящих заметок — какую удалить?");
            for (int i : pick.indices()) {
                NoteDto n = candidateAt(candidates, i);
                if (n != null) {
                    sb.append("\n• «").append(safeTitle(n)).append("»");
                }
            }
            return reply(sb.toString(), model);
        }
        NoteDto target = candidateAt(candidates, pick.indices().get(0));
        if (target == null) {
            return reply("Не нашёл такую заметку. Уточните, что удалить.", model);
        }
        String title = safeTitle(target);
        String confirm = "Удалить заметку «" + title + "»? Ответьте «да», чтобы удалить.";
        return new IntentResponse(manifest.name(), confirm, model, pendingAction(target.id(), title));
    }

    /**
     * Resume after the user replies to the confirmation. Affirmative → delete the stashed note; anything
     * else → leave it. Either reply carries no pendingAction, so the orchestrator clears the lock.
     */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        JsonNode pending = req.pendingAction();
        UUID noteId = noteId(pending);
        if (noteId == null) {
            return Mono.just(reply("Нечего удалять — повторите запрос, пожалуйста.", null));
        }
        String title = pending.path("title").asString("заметку");
        String text = req.message() == null ? null : req.message().text();
        if (!isAffirmative(text)) {
            return Mono.just(reply("Оставил «" + title + "» без изменений.", null));
        }
        return notes.delete(noteId)
                .then(Mono.just(reply("Удалил заметку «" + title + "».", null)))
                .onErrorResume(e -> {
                    log.warn("note-delete delete failed for {}: {}", noteId, e.toString());
                    return Mono.just(reply(
                            "Не смог удалить «" + title + "» — возможно, заметка уже удалена.", null));
                });
    }

    /** List notes are maintained through the list-manager (LI-a), not deleted wholesale here. */
    private static List<NoteDto> deletable(List<NoteDto> all) {
        if (all == null) {
            return List.of();
        }
        List<NoteDto> out = new ArrayList<>();
        for (NoteDto n : all) {
            if (!"list".equalsIgnoreCase(n.type())) {
                out.add(n);
            }
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
        }
        return out;
    }

    /** The numbered candidate list handed to the LLM ({@code {n, title, type?, snippet?}}). */
    private ArrayNode candidateList(List<NoteDto> candidates) {
        ArrayNode arr = json.createArrayNode();
        for (int i = 0; i < candidates.size(); i++) {
            NoteDto n = candidates.get(i);
            ObjectNode node = json.createObjectNode();
            node.put("n", i + 1);
            node.put("title", safeTitle(n));
            if (n.type() != null) {
                node.put("type", n.type());
            }
            String snippet = snippet(n.bodyMd());
            if (snippet != null) {
                node.put("snippet", snippet);
            }
            arr.add(node);
        }
        return arr;
    }

    private static String snippet(String bodyMd) {
        if (bodyMd == null || bodyMd.isBlank()) {
            return null;
        }
        String flat = bodyMd.strip().replaceAll("\\s+", " ");
        return flat.length() > SNIPPET_LEN ? flat.substring(0, SNIPPET_LEN) + "…" : flat;
    }

    /** Parse the LLM selection: {"pick":n} | {"ambiguous":[n,...]} | {} (none). 1-based indices. */
    private Pick parsePick(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode node;
        try {
            node = json.readTree(raw.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
        if (node.hasNonNull("pick") && node.get("pick").isNumber()) {
            return new Pick(List.of(node.get("pick").asInt()));
        }
        JsonNode ambiguous = node.get("ambiguous");
        if (ambiguous != null && ambiguous.isArray()) {
            List<Integer> ns = new ArrayList<>();
            ambiguous.forEach(n -> {
                if (n.isNumber()) {
                    ns.add(n.asInt());
                }
            });
            return new Pick(ns);
        }
        return null;
    }

    private static NoteDto candidateAt(List<NoteDto> candidates, int oneBased) {
        int i = oneBased - 1;
        return (i >= 0 && i < candidates.size()) ? candidates.get(i) : null;
    }

    private JsonNode pendingAction(UUID noteId, String title) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", FLOW);
        node.put("noteId", noteId.toString());
        node.put("title", title);
        return node;
    }

    private static UUID noteId(JsonNode pending) {
        if (pending == null || !pending.hasNonNull("noteId")) {
            return null;
        }
        try {
            return UUID.fromString(pending.get("noteId").asString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isAffirmative(String text) {
        return text != null && AFFIRMATIVE.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    private static String safeTitle(NoteDto n) {
        return (n.title() != null && !n.title().isBlank()) ? n.title() : "заметка";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "note-delete SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /** The resolved selection: one index (pick), several (ambiguous), or empty (none). */
    private record Pick(List<Integer> indices) {
    }
}
