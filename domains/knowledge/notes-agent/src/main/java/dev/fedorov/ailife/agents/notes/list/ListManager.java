package dev.fedorov.ailife.agents.notes.list;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.common.list.MarkdownChecklist;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Maintains the household's everyday item lists (LI-a, {@link dev.fedorov.ailife.agents.notes.list}).
 *
 * <p>A list is a {@code type=list} {@code memory.note} whose body is a CommonMark task list; each
 * operation reads the note, mutates the {@link MarkdownChecklist} body, and writes it back — no new
 * store. Pipeline: one llm-gateway {@code DEFAULT} turn with the {@code list-manager} SKILL classifies
 * the message into {@code {op, list, item}} (strict JSON, temperature 0) → resolve the list note
 * (find-or-create by title among the household's {@code type=list} notes) → apply the op → confirm.
 * Every stage soft-fails to a friendly reply.
 */
@Component
public class ListManager {

    private static final Logger log = LoggerFactory.getLogger(ListManager.class);
    private static final String SKILL_NAME = "list-manager";
    private static final String LIST_TYPE = "list";
    private static final String DEFAULT_SOURCE = "user";
    private static final String DEFAULT_LIST = "список покупок";
    private static final int LIST_SCAN_LIMIT = 500;   // notes to scan when resolving a list by title

    private final NoteClient notes;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public ListManager(NoteClient notes, LlmClient llm, SkillRegistry skills,
                       AgentManifest manifest, ObjectMapper json) {
        this.notes = notes;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> handle(NormalizedMessage msg) {
        String userText = msg == null ? null : msg.text();
        if (userText == null || userText.isBlank()) {
            return Mono.just(reply("Что сделать со списком? Например: «добавь молоко в список покупок»."));
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(userText)), 0.0);
        return llm.chat(request)
                .flatMap(r -> apply(msg, parse(r.content())))
                .onErrorResume(e -> {
                    log.warn("list operation failed: {}", e.toString());
                    return Mono.just(reply("Не удалось изменить список. Попробуйте позже."));
                });
    }

    private Mono<IntentResponse> apply(NormalizedMessage msg, Op op) {
        return findList(msg.householdId(), op.list())
                .flatMap(existing -> onExisting(op, existing))
                .switchIfEmpty(Mono.defer(() -> onMissing(msg, op)));
    }

    // ---- op dispatch on an existing list -------------------------------------------------

    private Mono<IntentResponse> onExisting(Op op, NoteDto note) {
        MarkdownChecklist list = MarkdownChecklist.parse(note.bodyMd());
        return switch (op.op()) {
            case "add" -> {
                if (blank(op.item())) {
                    yield Mono.just(reply("Что добавить в «" + note.title() + "»?"));
                }
                if (list.contains(op.item())) {
                    yield Mono.just(reply("«" + op.item().trim() + "» уже в списке «" + note.title() + "»."));
                }
                yield persist(note, list.add(op.item()))
                        .map(saved -> reply("Добавил «" + op.item().trim() + "» в «" + note.title() + "»."));
            }
            case "check" -> {
                if (blank(op.item())) {
                    yield Mono.just(reply("Что вычеркнуть из «" + note.title() + "»?"));
                }
                if (!list.contains(op.item())) {
                    yield Mono.just(reply("Не нашёл «" + op.item().trim() + "» в списке «" + note.title() + "»."));
                }
                if (list.isChecked(op.item())) {
                    yield Mono.just(reply("«" + op.item().trim() + "» уже вычеркнут в «" + note.title() + "»."));
                }
                yield persist(note, list.check(op.item()))
                        .map(saved -> reply("Вычеркнул «" + op.item().trim() + "» из «" + note.title() + "»."));
            }
            case "clear" -> {
                if (list.isEmpty()) {
                    yield Mono.just(reply("Список «" + note.title() + "» уже пуст."));
                }
                yield persist(note, list.clear())
                        .map(saved -> reply("Очистил список «" + note.title() + "»."));
            }
            case "show" -> Mono.just(reply(renderList(note.title(), list)));
            default -> Mono.just(reply("Не понял, что сделать со списком «" + note.title() + "»."));
        };
    }

    // ---- op dispatch when no such list exists -------------------------------------------

    private Mono<IntentResponse> onMissing(NormalizedMessage msg, Op op) {
        String name = listName(op.list());
        if ("add".equals(op.op())) {
            if (blank(op.item())) {
                return Mono.just(reply("Что добавить в «" + name + "»?"));
            }
            MarkdownChecklist created = MarkdownChecklist.parse(null).add(op.item());
            return notes.create(newListRequest(msg, name, created.render()))
                    .map(saved -> reply("Создал список «" + name + "» и добавил «" + op.item().trim() + "»."))
                    .onErrorResume(e -> {
                        log.warn("create list failed: {}", e.toString());
                        return Mono.just(reply("Не смог создать список. Попробуйте позже."));
                    });
        }
        // check / clear / show on a list that doesn't exist — never create an empty note.
        return Mono.just(reply("Нет списка «" + name + "»."));
    }

    // ---- helpers ------------------------------------------------------------------------

    private Mono<NoteDto> findList(java.util.UUID householdId, String listName) {
        String needle = listName(listName).toLowerCase(Locale.ROOT);
        return notes.list(householdId, LIST_SCAN_LIMIT)
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .filter(n -> LIST_TYPE.equalsIgnoreCase(n.type())
                        && n.title() != null
                        && n.title().trim().toLowerCase(Locale.ROOT).equals(needle))
                .next();
    }

    private Mono<NoteDto> persist(NoteDto note, MarkdownChecklist updated) {
        WriteNoteRequest req = new WriteNoteRequest(
                note.householdId(), note.ownerId(), note.title(), LIST_TYPE,
                note.tags(), firstNonBlank(note.source(), DEFAULT_SOURCE), note.personId(),
                updated.render(), note.frontmatter());
        return notes.update(note.id(), req);
    }

    private WriteNoteRequest newListRequest(NormalizedMessage msg, String name, String body) {
        return new WriteNoteRequest(
                msg.householdId(),
                null,                 // household-shared: the grocery list belongs to everyone
                name,
                LIST_TYPE,
                List.of(LIST_TYPE),
                DEFAULT_SOURCE,
                null,
                body,
                null);
    }

    private static String renderList(String title, MarkdownChecklist list) {
        if (list.isEmpty()) {
            return "Список «" + title + "» пуст.";
        }
        StringBuilder sb = new StringBuilder("Список «").append(title).append("»:");
        for (MarkdownChecklist.Item it : list.items()) {
            sb.append('\n').append(it.done() ? "✅ " : "⬜ ").append(it.text());
        }
        return sb.toString();
    }

    private static String listName(String raw) {
        return blank(raw) ? DEFAULT_LIST : raw.trim();
    }

    private Op parse(String content) {
        JsonNode node = parseObject(content);
        String op = normalizeOp(text(node, "op"));
        return new Op(op, text(node, "list"), text(node, "item"));
    }

    private static String normalizeOp(String op) {
        if (op == null) {
            return "show";
        }
        String o = op.trim().toLowerCase(Locale.ROOT);
        return switch (o) {
            case "add", "check", "clear", "show" -> o;
            default -> "show";
        };
    }

    private JsonNode parseObject(String content) {
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

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "list-manager SKILL.md not loaded — check skills-classpath"));
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asString();
        return v.isBlank() ? null : v;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private IntentResponse reply(String text) {
        return new IntentResponse(manifest.name(), text, null);
    }

    /** The classified list operation from the {@code list-manager} SKILL. */
    private record Op(String op, String list, String item) {
    }
}
