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
import dev.fedorov.ailife.contracts.tasks.AddTaskInput;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.sharing.SharingContext;
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
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public TaskCapturer(LlmClient llm,
                        AddTaskClient tasks,
                        SharingResolver sharing,
                        SkillRegistry skills,
                        AgentManifest manifest,
                        ObjectMapper json) {
        this.llm = llm;
        this.tasks = tasks;
        this.sharing = sharing;
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
            // The task is routed to the personal or the shared household by the resolver + policy; the
            // LLM's "shared" read (a chore / shared list / involves another member) is the only signal.
            // No explicit scope is threaded — tasks' default policy reads the context.
            SharingContext ctx = new SharingContext(List.of(), plan.shared(), "task");
            return sharing.resolveHousehold(userId, null, ctx, envelopeHousehold)
                    .switchIfEmpty(Mono.error(new IllegalStateException("no resolvable household")))
                    .flatMap(household -> tasks.add(new AddTaskInput(
                            household, null, plan.title(), plan.note(), SOURCE))
                            .map(dto -> new CaptureResult(summary(dto.title(), plan.shared()), model)));
        });
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
        String title = node.path("title").asText("").trim();
        if (title.isEmpty()) {
            return null;
        }
        String note = node.hasNonNull("note") ? node.get("note").asText().trim() : null;
        boolean shared = node.path("shared").asBoolean(false);
        return new Planned(title, (note == null || note.isEmpty()) ? null : note, shared);
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

    /** One planned task: title, optional note, and whether it belongs on the shared household list. */
    private record Planned(String title, String note, boolean shared) {
    }

    /** The chat reply (a short confirmation) plus the model that produced the plan. */
    public record CaptureResult(String text, String model) {
    }
}
