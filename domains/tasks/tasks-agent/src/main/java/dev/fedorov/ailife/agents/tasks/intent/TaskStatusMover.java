package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Phrasing;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.ClarifyClient;
import dev.fedorov.ailife.agents.tasks.read.TaskReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.tasks.ClarifyTaskInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the reactive {@code task-status} intent skill (road-test #486, Track H.2 — the tasks per-domain
 * <b>state-move</b> hole): move a task between GTD states by just chatting ("отметь задачу про молоко
 * выполненной", "перенеси задачу про врача в ожидание", "отложи задачу X"), behind a
 * <b>confirm-before-change</b> gate. When {@code IntentRouter} classifies a message as a status move,
 * {@link dev.fedorov.ailife.agents.tasks.web.IntentController} dispatches to {@link #move}; the confirming
 * reply routes back through {@code ResumeController} to {@link #resume}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this class
 * is the tasks status adapter (the sibling of {@code TaskEditor}, which edits <i>content</i> — title/due/note
 * — via {@code update_task}). A status change is a different GTD verb, so {@link #act} routes it through
 * {@code clarify_task} (mcp-tasks {@code POST /internal/clarify}, which keeps {@code completed_at} consistent
 * with the {@code done} state) rather than {@code update_task}. The LLM picks the target task <b>and</b> the
 * new status (one of the six GTD states); a picked task with no valid status re-asks ({@link #missing}).
 */
@Component
public class TaskStatusMover
        implements TargetedActionFlow<TaskItemDto>, CandidateView<TaskItemDto>, Phrasing<TaskItemDto> {

    public static final String SKILL_NAME = "task-status";
    /** pendingAction discriminator the tasks ResumeController dispatches on. */
    public static final String FLOW = "task-status-confirm";
    private static final int MAX_CANDIDATES = 40;

    /** The six GTD statuses (mcp-tasks' whitelist) and their user-facing Russian labels. */
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "inbox", "инбокс",
            "next", "следующее действие",
            "waiting", "ожидание",
            "scheduled", "запланирована",
            "done", "выполнена",
            "dropped", "отменена");

    private final TaskReads taskReads;
    private final ClarifyClient clarify;
    private final PickConfirmActRunner<TaskItemDto> runner;

    public TaskStatusMover(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                           TaskReads taskReads, ClarifyClient clarify, ObjectMapper json) {
        this.taskReads = taskReads;
        this.clarify = clarify;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the owner's tasks, let the LLM pick the target + new status, and reply with a confirm. */
    public Mono<IntentResponse> move(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative applies the stashed status; anything else leaves it. */
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
        return Set.of("готово", "сделал", "сделано", "выполнил", "отметь", "перенеси", "отложи");
    }

    @Override
    public Mono<List<TaskItemDto>> candidates(NormalizedMessage msg) {
        // Search everywhere the user can see (personal ∪ shared), any status, so a done task is reachable too.
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

    /** Picked a task but the LLM gave no valid GTD status → ask which state, without a lock. */
    @Override
    public Optional<String> missing(TaskItemDto target, JsonNode pick) {
        return status(pick) == null
                ? Optional.of("В какой статус перевести «" + safeTitle(target)
                        + "»? Например: выполнена, ожидание, запланирована, инбокс, отменена.")
                : Optional.empty();
    }

    /** The resume needs the stashed status, not just the id. */
    @Override
    public boolean readyToAct(JsonNode pending) {
        return status(pending) != null;
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode pending) {
        String status = status(pending);
        return clarify.clarify(new ClarifyTaskInput(targetId, status, null, null, null, null, null)).then();
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
        return "Какую задачу и в какой статус перевести?";
    }

    @Override
    public String noHousehold() {
        return "Не понял, в каком списке искать задачу.";
    }

    @Override
    public String emptyPool() {
        return "Не нашёл задач, статус которых можно изменить.";
    }

    @Override
    public String noMatch() {
        return "Не нашёл такую задачу. Уточните, какую перевести.";
    }

    @Override
    public String readFailed() {
        return "Не смог найти задачу для смены статуса. Попробуйте ещё раз позже.";
    }

    @Override
    public String notReady() {
        return "Не понял, в какой статус перевести — повторите запрос, пожалуйста.";
    }

    @Override
    public String ambiguous(List<TaskItemDto> picks) {
        StringBuilder sb = new StringBuilder("Нашёл несколько подходящих задач — какую перевести?");
        for (TaskItemDto t : picks) {
            sb.append("\n• «").append(safeTitle(t)).append("»");
        }
        return sb.toString();
    }

    @Override
    public String confirm(TaskItemDto target, JsonNode pick) {
        String status = status(pick);
        if ("done".equals(status)) {
            return "Отметить задачу «" + safeTitle(target) + "» выполненной? Ответьте «да».";
        }
        return "Перевести задачу «" + safeTitle(target) + "» в статус «" + label(status)
                + "»? Ответьте «да», чтобы сохранить.";
    }

    @Override
    public String declined(JsonNode pending) {
        return "Оставил «" + title(pending) + "» без изменений.";
    }

    @Override
    public String done(JsonNode pending) {
        String status = status(pending);
        if ("done".equals(status)) {
            return "Отметил «" + title(pending) + "» выполненной.";
        }
        return "Перевёл «" + title(pending) + "» в статус «" + label(status) + "».";
    }

    @Override
    public String actFailed(JsonNode pending) {
        return "Не смог изменить статус «" + title(pending) + "» — возможно, задача уже удалена.";
    }

    /** The normalised GTD status on a node ({@code newStatus}), or null when absent / not one of the six. */
    private static String status(JsonNode node) {
        if (node == null || !node.hasNonNull("newStatus")) {
            return null;
        }
        String v = node.get("newStatus").asString().strip().toLowerCase(Locale.ROOT);
        return STATUS_LABELS.containsKey(v) ? v : null;
    }

    private static String label(String status) {
        return STATUS_LABELS.getOrDefault(status, status);
    }

    private static String title(JsonNode pending) {
        return pending.path("title").asString("задачу");
    }

    private static String safeTitle(TaskItemDto t) {
        return (t.title() != null && !t.title().isBlank()) ? t.title() : "задача";
    }
}
