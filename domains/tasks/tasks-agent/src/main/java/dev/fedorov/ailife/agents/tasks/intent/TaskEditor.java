package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Phrasing;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.UpdateTaskClient;
import dev.fedorov.ailife.agents.tasks.read.TaskReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.contracts.tasks.UpdateTaskInput;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the reactive {@code task-edit} intent skill (road-test #486, Track H.2 — the tasks per-domain
 * <b>edit</b> hole): rename / reschedule / correct a task by just chatting ("переименуй задачу про молоко в
 * …", "перенеси срок задачи про врача на завтра"), behind a <b>confirm-before-change</b> gate. When
 * {@code IntentRouter} classifies a message as an edit request,
 * {@link dev.fedorov.ailife.agents.tasks.web.IntentController} dispatches to {@link #edit}; the confirming
 * reply routes back through {@code ResumeController} to {@link #resume}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this class
 * is the tasks-edit adapter. The LLM picks the target task <b>and</b> extracts the change the user gave
 * (a new title / due / note), threaded through the {@code pendingAction} like a calendar move's new time. A
 * picked task with no stated change re-asks ({@link #missing}); the terminal {@link #act} PUTs only the
 * changed fields via mcp-tasks {@code PUT /internal/task/{id}} (partial edit — untouched fields are
 * preserved). Status moves ("сделал"/"готово") are a different verb (complete/clarify), not this flow.
 */
@Component
public class TaskEditor
        implements TargetedActionFlow<TaskItemDto>, CandidateView<TaskItemDto>, Phrasing<TaskItemDto> {

    public static final String SKILL_NAME = "task-edit";
    /** pendingAction discriminator the tasks ResumeController dispatches on. */
    public static final String FLOW = "task-edit-confirm";
    private static final int MAX_CANDIDATES = 40;
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru")).withZone(ZoneOffset.UTC);

    private final TaskReads taskReads;
    private final UpdateTaskClient updateTask;
    private final PickConfirmActRunner<TaskItemDto> runner;

    public TaskEditor(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                      TaskReads taskReads, UpdateTaskClient updateTask, ObjectMapper json) {
        this.taskReads = taskReads;
        this.updateTask = updateTask;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the owner's open tasks, let the LLM pick + extract the change, and reply with a confirm. */
    public Mono<IntentResponse> edit(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative applies the stashed change; anything else leaves it. */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        return runner.resume(req);
    }

    // ----- TargetedActionFlow -----------------------------------------------------------------------

    @Override
    public String skillName() {
        return SKILL_NAME;
    }

    @Override
    public String flow() {
        return FLOW;
    }

    @Override
    public String idField() {
        return "taskId";
    }

    @Override
    public String labelField() {
        return "title";
    }

    @Override
    public Set<String> extraAffirmatives() {
        return Set.of("исправь", "исправить", "переименуй", "перенеси", "сохрани", "сохранить", "save");
    }

    /** The SKILL resolves relative due dates ("на завтра") against the current instant. */
    @Override
    public void decorateUserMessage(ObjectNode userMsg) {
        userMsg.put("now", Instant.now().toString());
    }

    @Override
    public Mono<List<TaskItemDto>> candidates(NormalizedMessage msg) {
        // Search everywhere the user can see (personal ∪ shared) so a shared task is editable too.
        return taskReads.households(msg.householdId(), msg.userId(), true)
                .flatMap(households -> taskReads.openTasksUnion(households, MAX_CANDIDATES));
    }

    @Override
    public CandidateView<TaskItemDto> view() {
        return this;
    }

    @Override
    public Phrasing<TaskItemDto> phrasing() {
        return this;
    }

    /** Picked a task but the user did not say what to change → ask, without a lock. */
    @Override
    public Optional<String> missing(TaskItemDto target, JsonNode pick) {
        return hasChange(pick)
                ? Optional.empty()
                : Optional.of("Что изменить в задаче «" + safeTitle(target)
                        + "»? Новое название, срок или заметку.");
    }

    /** The resume needs at least one stashed change, not just the id. */
    @Override
    public boolean readyToAct(JsonNode pending) {
        return hasChange(pending);
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode pending) {
        String newTitle = text(pending, "newTitle");
        String newNote = text(pending, "newNote");
        Instant newDue = instant(pending, "newDue");
        UpdateTaskInput input = new UpdateTaskInput(
                targetId, newTitle, newNote, null, null, null, newDue, null);
        return updateTask.update(targetId, input).then();
    }

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(TaskItemDto t) {
        return t.id();
    }

    @Override
    public String label(TaskItemDto t) {
        return safeTitle(t);
    }

    @Override
    public void describe(ObjectNode node, TaskItemDto t) {
        node.put("title", safeTitle(t));
        if (t.status() != null) {
            node.put("status", t.status());
        }
        if (t.dueAt() != null) {
            node.put("dueAt", t.dueAt().toString());
        }
    }

    // ----- Phrasing ---------------------------------------------------------------------------------

    @Override
    public String askWhich() {
        return "Какую задачу исправить и что изменить?";
    }

    @Override
    public String noHousehold() {
        return "Не понял, в каком списке искать задачу.";
    }

    @Override
    public String emptyPool() {
        return "Не нашёл задач, которые можно изменить.";
    }

    @Override
    public String noMatch() {
        return "Не нашёл такую задачу. Уточните, что изменить.";
    }

    @Override
    public String readFailed() {
        return "Не смог найти задачу для правки. Попробуйте ещё раз позже.";
    }

    @Override
    public String notReady() {
        return "Нечего менять — повторите запрос, пожалуйста.";
    }

    @Override
    public String ambiguous(List<TaskItemDto> picks) {
        StringBuilder sb = new StringBuilder("Нашёл несколько подходящих задач — какую изменить?");
        for (TaskItemDto t : picks) {
            sb.append("\n• «").append(safeTitle(t)).append("»");
        }
        return sb.toString();
    }

    @Override
    public String confirm(TaskItemDto target, JsonNode pick) {
        String newTitle = text(pick, "newTitle");
        Instant newDue = instant(pick, "newDue");
        StringBuilder sb = new StringBuilder("Изменить задачу «").append(safeTitle(target)).append("»");
        if (newTitle != null) {
            sb.append(" → «").append(newTitle).append("»");
        }
        if (newDue != null) {
            sb.append(" (срок ").append(WHEN.format(newDue)).append(")");
        }
        if (text(pick, "newNote") != null) {
            sb.append(" (новая заметка)");
        }
        return sb.append("? Ответьте «да», чтобы сохранить.").toString();
    }

    @Override
    public String declined(JsonNode pending) {
        return "Оставил «" + title(pending) + "» без изменений.";
    }

    @Override
    public String done(JsonNode pending) {
        return "Изменил задачу «" + title(pending) + "».";
    }

    @Override
    public String actFailed(JsonNode pending) {
        return "Не смог изменить «" + title(pending) + "» — возможно, задача уже удалена.";
    }

    private static boolean hasChange(JsonNode node) {
        return text(node, "newTitle") != null || text(node, "newNote") != null
                || instant(node, "newDue") != null;
    }

    /** A present, non-blank string field, else null. */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asString().strip();
        return v.isEmpty() ? null : v;
    }

    private static Instant instant(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String title(JsonNode pending) {
        return pending.path("title").asString("задачу");
    }

    private static String safeTitle(TaskItemDto t) {
        return (t.title() != null && !t.title().isBlank()) ? t.title() : "задача";
    }
}
