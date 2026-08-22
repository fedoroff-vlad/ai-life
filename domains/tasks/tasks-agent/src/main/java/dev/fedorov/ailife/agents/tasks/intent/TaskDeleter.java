package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.DeleteTaskClient;
import dev.fedorov.ailife.agents.tasks.read.TaskReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
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
 * Runs the reactive {@code task-delete} intent skill (road-test #486, Track H.2 — the tasks per-domain
 * delete hole): delete a task by just chatting ("удали задачу про X"), behind the standing
 * <b>confirm-before-delete</b> gate (a tasks principle; see AGENT.md). When {@link IntentRouter} classifies
 * a message as a delete request, {@link dev.fedorov.ailife.agents.tasks.web.IntentController} dispatches here.
 *
 * <p>Two turns over the Stage-4 pending-action lock (mirrors {@link InboxClarifier} + calendar's
 * {@code EventCanceller}):
 * <ol>
 *   <li>{@link #delete} — read the owner's open tasks (personal ∪ shared via the sharing-aware
 *       {@link TaskReads}), let the LLM ({@code task-delete} SKILL, temperature 0) pick which candidate they
 *       mean, and reply with a {@code pendingAction} asking to confirm the deletion (route-locks to tasks).
 *       Nothing is deleted yet. An ambiguous / unmatched target lists / asks instead of guessing.</li>
 *   <li>{@link #resume} — on the reply: an affirmative deletes the stashed task via
 *       {@link DeleteTaskClient} (mcp-tasks {@code DELETE /internal/task/{id}}); anything else leaves it.</li>
 * </ol>
 * Every stage soft-fails to a friendly Russian message (no pendingAction → no lock).
 */
@Component
public class TaskDeleter {

    private static final Logger log = LoggerFactory.getLogger(TaskDeleter.class);
    public static final String SKILL_NAME = "task-delete";
    /** pendingAction discriminator the tasks ResumeController dispatches on. */
    public static final String FLOW = "task-delete-confirm";
    private static final int MAX_CANDIDATES = 40;
    private static final Set<String> AFFIRMATIVE = Set.of(
            "да", "ага", "верно", "удали", "удалить", "убери", "убрать", "ок", "окей", "давай", "+",
            "yes", "y", "ok", "delete", "confirm");

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final TaskReads taskReads;
    private final DeleteTaskClient deleteTask;
    private final ObjectMapper json;

    public TaskDeleter(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                       TaskReads taskReads, DeleteTaskClient deleteTask, ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.taskReads = taskReads;
        this.deleteTask = deleteTask;
        this.json = json;
    }

    public Mono<IntentResponse> delete(NormalizedMessage msg) {
        String userText = msg == null ? null : msg.text();
        if (userText == null || userText.isBlank()) {
            return Mono.just(reply("Какую задачу удалить?", null));
        }
        if (msg.householdId() == null) {
            return Mono.just(reply("Не знаю, к какому хозяйству относится запрос.", null));
        }
        // Search everywhere the user can see (personal ∪ shared) so a shared task is findable too.
        return taskReads.households(msg.householdId(), msg.userId(), true)
                .flatMap(households -> taskReads.openTasksUnion(households, MAX_CANDIDATES))
                .flatMap(tasks -> resolveAndConfirm(userText, tasks))
                .onErrorResume(e -> {
                    log.warn("task-delete failed: {}", e.toString());
                    return Mono.just(reply("Не смог найти задачу для удаления. Попробуйте ещё раз позже.", null));
                });
    }

    private Mono<IntentResponse> resolveAndConfirm(String userText, List<TaskItemDto> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Mono.just(reply("Не нашёл задач, которые можно удалить.", null));
        }
        List<TaskItemDto> candidates = tasks.size() > MAX_CANDIDATES ? tasks.subList(0, MAX_CANDIDATES) : tasks;

        ObjectNode userMsg = json.createObjectNode();
        userMsg.put("userText", userText);
        userMsg.set("candidates", candidateList(candidates));

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(userMsg.toString())), 0.0);

        return llm.chat(req).map(resp -> pickReply(parsePick(resp.content()), candidates, resp.model()));
    }

    private IntentResponse pickReply(Pick pick, List<TaskItemDto> candidates, String model) {
        if (pick == null || pick.indices().isEmpty()) {
            return reply("Не нашёл такую задачу. Уточните, что удалить.", model);
        }
        if (pick.indices().size() > 1) {
            StringBuilder sb = new StringBuilder("Нашёл несколько подходящих задач — какую удалить?");
            for (int i : pick.indices()) {
                TaskItemDto t = candidateAt(candidates, i);
                if (t != null) {
                    sb.append("\n• «").append(safeTitle(t)).append("»");
                }
            }
            return reply(sb.toString(), model);
        }
        TaskItemDto target = candidateAt(candidates, pick.indices().get(0));
        if (target == null) {
            return reply("Не нашёл такую задачу. Уточните, что удалить.", model);
        }
        String title = safeTitle(target);
        String confirm = "Удалить задачу «" + title + "»? Ответьте «да», чтобы удалить.";
        return new IntentResponse(manifest.name(), confirm, model, pendingAction(target.id(), title));
    }

    /**
     * Resume after the user replies to the confirmation. Affirmative → delete the stashed task; anything
     * else → leave it. Either reply carries no pendingAction, so the orchestrator clears the lock.
     */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        JsonNode pending = req.pendingAction();
        UUID taskId = taskId(pending);
        if (taskId == null) {
            return Mono.just(reply("Нечего удалять — повторите запрос, пожалуйста.", null));
        }
        String title = pending.path("title").asString("задачу");
        String text = req.message() == null ? null : req.message().text();
        if (!isAffirmative(text)) {
            return Mono.just(reply("Оставил «" + title + "» без изменений.", null));
        }
        return deleteTask.delete(taskId)
                .map(deleted -> reply("Удалил задачу «" + title + "».", null))
                .onErrorResume(e -> {
                    log.warn("task-delete delete failed for {}: {}", taskId, e.toString());
                    return Mono.just(reply(
                            "Не смог удалить «" + title + "» — возможно, задача уже удалена.", null));
                });
    }

    /** The numbered candidate list handed to the LLM ({@code {n, title, status, dueAt?}}). */
    private ArrayNode candidateList(List<TaskItemDto> candidates) {
        ArrayNode arr = json.createArrayNode();
        for (int i = 0; i < candidates.size(); i++) {
            TaskItemDto t = candidates.get(i);
            ObjectNode node = json.createObjectNode();
            node.put("n", i + 1);
            node.put("title", safeTitle(t));
            if (t.status() != null) {
                node.put("status", t.status());
            }
            if (t.dueAt() != null) {
                node.put("dueAt", t.dueAt().toString());
            }
            arr.add(node);
        }
        return arr;
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

    private static TaskItemDto candidateAt(List<TaskItemDto> candidates, int oneBased) {
        int i = oneBased - 1;
        return (i >= 0 && i < candidates.size()) ? candidates.get(i) : null;
    }

    private JsonNode pendingAction(UUID taskId, String title) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", FLOW);
        node.put("taskId", taskId.toString());
        node.put("title", title);
        return node;
    }

    private static UUID taskId(JsonNode pending) {
        if (pending == null || !pending.hasNonNull("taskId")) {
            return null;
        }
        try {
            return UUID.fromString(pending.get("taskId").asString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isAffirmative(String text) {
        return text != null && AFFIRMATIVE.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    private static String safeTitle(TaskItemDto t) {
        return (t.title() != null && !t.title().isBlank()) ? t.title() : "задача";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "task-delete SKILL.md not loaded — check skills-classpath"));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    /** The resolved selection: one index (pick), several (ambiguous), or empty (none). */
    private record Pick(List<Integer> indices) {
    }
}
