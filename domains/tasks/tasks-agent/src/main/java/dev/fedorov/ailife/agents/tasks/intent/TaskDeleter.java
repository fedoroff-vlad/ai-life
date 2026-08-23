package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.intent.CandidateView;
import dev.fedorov.ailife.agentruntime.intent.Nouns;
import dev.fedorov.ailife.agentruntime.intent.PickConfirmActRunner;
import dev.fedorov.ailife.agentruntime.intent.TargetedActionFlow;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.DeleteTaskClient;
import dev.fedorov.ailife.agents.tasks.read.TaskReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Runs the reactive {@code task-delete} intent skill (road-test #486, Track H.2 — the tasks per-domain
 * delete hole): delete a task by just chatting ("удали задачу про X"), behind the standing
 * <b>confirm-before-delete</b> gate (a tasks principle; see AGENT.md). When {@code IntentRouter} classifies
 * a message as a delete request, {@link dev.fedorov.ailife.agents.tasks.web.IntentController} dispatches to
 * {@link #delete}; the confirming reply routes back through {@code ResumeController} to {@link #resume}.
 *
 * <p>The pick→confirm→act loop itself lives in the shared {@link PickConfirmActRunner} (ADR-0004); this
 * class is the tasks adapter — it names the target ({@link #nouns}), reads the candidate pool (open tasks,
 * personal ∪ shared via the sharing-aware {@link TaskReads}), renders a candidate ({@link CandidateView}),
 * and performs the delete ({@link #act} via mcp-tasks {@code DELETE /internal/task/{id}}).
 */
@Component
public class TaskDeleter implements TargetedActionFlow<TaskItemDto>, CandidateView<TaskItemDto> {

    public static final String SKILL_NAME = "task-delete";
    /** pendingAction discriminator the tasks ResumeController dispatches on. */
    public static final String FLOW = "task-delete-confirm";
    private static final int MAX_CANDIDATES = 40;

    private final TaskReads taskReads;
    private final DeleteTaskClient deleteTask;
    private final PickConfirmActRunner<TaskItemDto> runner;

    public TaskDeleter(LlmClient llm, AgentManifest manifest, SkillRegistry skills,
                       TaskReads taskReads, DeleteTaskClient deleteTask, ObjectMapper json) {
        this.taskReads = taskReads;
        this.deleteTask = deleteTask;
        this.runner = new PickConfirmActRunner<>(llm, manifest, skills, json, this);
    }

    /** Turn 1: read the owner's open tasks, let the LLM pick, and reply with a confirm {@code pendingAction}. */
    public Mono<IntentResponse> delete(NormalizedMessage msg) {
        return runner.pick(msg);
    }

    /** Turn 2: an affirmative deletes the stashed task; anything else leaves it. */
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
    public Nouns nouns() {
        return new Nouns("задачу", "задач", "задача");
    }

    @Override
    public Mono<List<TaskItemDto>> candidates(NormalizedMessage msg) {
        // Search everywhere the user can see (personal ∪ shared) so a shared task is findable too.
        return taskReads.households(msg.householdId(), msg.userId(), true)
                .flatMap(households -> taskReads.openTasksUnion(households, MAX_CANDIDATES));
    }

    @Override
    public CandidateView<TaskItemDto> view() {
        return this;
    }

    @Override
    public Mono<Void> act(UUID targetId, JsonNode params) {
        return deleteTask.delete(targetId).then();
    }

    // ----- CandidateView ----------------------------------------------------------------------------

    @Override
    public UUID id(TaskItemDto t) {
        return t.id();
    }

    @Override
    public String label(TaskItemDto t) {
        return "«" + safeTitle(t) + "»";
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

    private static String safeTitle(TaskItemDto t) {
        return (t.title() != null && !t.title().isBlank()) ? t.title() : "задача";
    }
}
