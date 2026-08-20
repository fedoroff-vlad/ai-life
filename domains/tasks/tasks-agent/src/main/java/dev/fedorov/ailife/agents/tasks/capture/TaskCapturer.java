package dev.fedorov.ailife.agents.tasks.capture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.AddTaskClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.agents.tasks.sharing.TasksSharingPolicy;
import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.tasks.AddTaskInput;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.sharing.SharingConfirm;
import dev.fedorov.ailife.sharing.SharingContext;
import dev.fedorov.ailife.sharing.SharingResolution;
import dev.fedorov.ailife.sharing.SharingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The tasks domain's sharing <b>write path</b> (ADR-0002 slice 5) — turn a plain-language capture
 * ("напомни купить молоко", "нужно вынести мусор — это общее") into a task routed to the right household
 * (personal vs shared). Routed here by {@link dev.fedorov.ailife.agents.tasks.intent.IntentRouter} /
 * {@code IntentController} as the {@code task-capture} intent skill.
 *
 * <p>This is the deterministic capture path an LLM-driven {@code add_task} tool call cannot take: the
 * classifier never sees the household id, so a generic tool call has no way to pick the personal-vs-shared
 * household. Here the household comes from the shared {@link SharingResolver} instead. Flow:
 * <ol>
 *   <li>ask the LLM — with the {@code task-capture} SKILL as the instruction — for a strict-JSON plan
 *       ({@code {title, note?, shared?}});</li>
 *   <li>build a {@link SharingContext} whose {@code involvesHouseholdMember} carries the LLM's read of
 *       whether the task belongs on the <b>household / shared list</b> (a chore, shared shopping, a task
 *       involving another member);</li>
 *   <li>let the shared {@link SharingResolver} route it to a concrete {@code household_id} — a shared task
 *       lands in the family household, a personal one in the member's own (tasks' {@code TasksSharingPolicy}
 *       default). The routing/fallback rules live once in {@code libs/sharing}; this flow only supplies the
 *       signals;</li>
 *   <li>capture it via mcp-tasks' {@code POST /internal/task} ({@link AddTaskClient}) as a fresh inbox item.</li>
 * </ol>
 * mcp-tasks stays tenant-agnostic — it writes to whatever household it is handed. Returns a short Russian
 * confirmation naming the task and whether it went to the shared list. Mirrors finance's {@code AccountManager}.
 */
@Component
public class TaskCapturer {

    private static final Logger log = LoggerFactory.getLogger(TaskCapturer.class);
    public static final String SKILL_NAME = "task-capture";
    private static final String SOURCE = "telegram";

    private final LlmClient llm;
    private final AddTaskClient tasks;
    private final SharingResolver sharing;
    private final SharingConfirm sharingConfirm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public TaskCapturer(LlmClient llm,
                        AddTaskClient tasks,
                        SharingResolver sharing,
                        SharingConfirm sharingConfirm,
                        SkillRegistry skills,
                        AgentManifest manifest,
                        ObjectMapper json) {
        this.llm = llm;
        this.tasks = tasks;
        this.sharing = sharing;
        this.sharingConfirm = sharingConfirm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<CaptureResult> capture(NormalizedMessage msg) {
        UUID envelopeHousehold = msg == null ? null : msg.householdId();
        UUID userId = msg == null ? null : msg.userId();
        if (envelopeHousehold == null && userId == null) {
            return Mono.just(new CaptureResult("Не вижу, к какому хозяйству отнести задачу.", null));
        }
        return planAndCapture(msg, userId, envelopeHousehold)
                .onErrorResume(e -> {
                    log.warn("task-capture failed: {}", e.toString());
                    return Mono.just(new CaptureResult(
                            "Не смог записать задачу. Попробуйте ещё раз чуть позже.", null));
                });
    }

    private Mono<CaptureResult> planAndCapture(NormalizedMessage msg, UUID userId, UUID envelopeHousehold) {
        ObjectNode userMsg = json.createObjectNode();
        userMsg.put("userText", msg == null || msg.text() == null ? "" : msg.text());

        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(userMsg.toString())));

        return llm.chat(req).flatMap(resp -> {
            String model = resp.model();
            Planned plan = parsePlan(resp.content());
            if (plan == null) {
                return Mono.just(new CaptureResult(
                        "Не понял, что записать. Скажите, например: «напомни купить молоко».", model));
            }
            // The task is routed to the personal or the shared household by the resolver + policy. The LLM's
            // "shared" read is the signal when it could tell; when it couldn't (no scope signal at all) the
            // context carries the UNSCOPED kind so the policy abstains (item 8, DS-N) and the resolver asks.
            String kind = plan.scopeKnown() ? "task" : TasksSharingPolicy.UNSCOPED_KIND;
            SharingContext ctx = new SharingContext(List.of(), plan.shared(), kind);
            return sharing.resolve(userId, null, ctx, envelopeHousehold).flatMap(res -> switch (res) {
                case SharingResolution.Resolved r when r.household() != null ->
                        captureInto(r.household(), plan.title(), plan.note(), plan.shared(), model);
                case SharingResolution.Resolved ignored ->
                        Mono.just(new CaptureResult("Не понял, к какому хозяйству отнести задачу.", model));
                // Genuinely ambiguous → defer + ask "личное или общее?" (SharingConfirm owns the plumbing).
                case SharingResolution.NeedsConfirm nc -> Mono.just(askScope(nc, plan, model));
            });
        });
    }

    /** Capture a task under a resolved household and confirm; {@code shared} only shapes the reply text. */
    private Mono<CaptureResult> captureInto(UUID household, String title, String note, boolean shared, String model) {
        return tasks.add(new AddTaskInput(household, null, title, note, SOURCE))
                .map(dto -> new CaptureResult(summary(dto.title(), shared), model, null,
                        "wrote: captured a task to the " + (shared ? "shared" : "personal") + " list"));
    }

    /** Build the deferred "личное или общее?" ask, stashing the plan so {@link #finishCapture} can capture it. */
    private CaptureResult askScope(SharingResolution.NeedsConfirm nc, Planned plan, String model) {
        ObjectNode stash = json.createObjectNode();
        stash.put("title", plan.title());
        if (plan.note() != null) {
            stash.put("note", plan.note());
        }
        return new CaptureResult(
                sharingConfirm.question("«" + plan.title() + "»"), model,
                sharingConfirm.pendingAction(nc, stash));
    }

    /**
     * The {@link SharingConfirm.Finish} callback (hit from the tasks {@code ResumeController}): the owner
     * answered личное/общее, the resolver already picked + recorded the household — now capture the stashed
     * task into it. Returns the confirmation text; {@code chosen} only shapes whether we say "в общий список".
     */
    public Mono<String> finishCapture(UUID household, SharingScope chosen, JsonNode stash) {
        String title = stash == null ? "" : stash.path("title").asString("");
        String note = (stash != null && stash.hasNonNull("note")) ? stash.get("note").asString() : null;
        return tasks.add(new AddTaskInput(household, null, title, note, SOURCE))
                .map(dto -> summary(dto.title(), chosen == SharingScope.SHARED));
    }

    /** Parse the single-task LLM plan; null when no task was described. */
    private Planned parsePlan(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode node;
        try {
            node = json.readTree(trimmed.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
        String title = node.path("title").asString("").trim();
        if (title.isEmpty()) {
            return null;
        }
        String note = node.hasNonNull("note") ? node.get("note").asString().trim() : null;
        // Tri-state: the LLM sets `shared` true/false when it can tell, and omits it (or null) when the
        // capture gives no personal/shared signal — which is the DS-N "ask" trigger (scopeKnown=false).
        boolean scopeKnown = node.hasNonNull("shared");
        boolean shared = node.path("shared").asBoolean(false);
        return new Planned(title, (note == null || note.isEmpty()) ? null : note, shared, scopeKnown);
    }

    private String summary(String title, boolean shared) {
        return "Записал" + (shared ? " в общий список" : "") + ": «" + title + "».";
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "task-capture SKILL.md not loaded — check skills-classpath"));
    }

    /**
     * One planned task: title, optional note, whether it belongs on the shared household list, and whether the
     * LLM could tell at all ({@code scopeKnown} false ⇒ ambiguous ⇒ ask, item 8 DS-N).
     */
    private record Planned(String title, String note, boolean shared, boolean scopeKnown) {
    }

    /**
     * The chat reply (a short confirmation) plus the model that produced the plan, an optional
     * {@code pendingAction} — non-null when the capture was deferred to ask "личное или общее?" (DS-N), which
     * {@code IntentController} threads into the {@code IntentResponse} so the orchestrator locks the conversation
     * — and an optional payload-free {@code trace} of what was written (why-trace #485/G2c), set only on a
     * terminal successful capture (a deferred/failed turn leaves it null → the explain answer falls back to
     * the routing-only trace).
     */
    public record CaptureResult(String text, String model, JsonNode pendingAction, String trace) {
        public CaptureResult(String text, String model) {
            this(text, model, null, null);
        }

        public CaptureResult(String text, String model, JsonNode pendingAction) {
            this(text, model, pendingAction, null);
        }
    }
}
