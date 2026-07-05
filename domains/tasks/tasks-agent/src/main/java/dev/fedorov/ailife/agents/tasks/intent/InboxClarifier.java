package dev.fedorov.ailife.agents.tasks.intent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.ClarifyClient;
import dev.fedorov.ailife.agents.tasks.http.TaskReviewClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.tasks.ClarifyTaskInput;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the reactive {@code inbox-clarify} skill, <b>apply-on-confirm</b> (Stage 4 / A4). When
 * {@link IntentRouter} classifies a user message as a request to clarify the GTD inbox, this fetches
 * the un-clarified inbox (mcp-tasks {@code /internal/review} via {@link TaskReviewClient}) and asks
 * the LLM (AGENT.md + the {@code inbox-clarify} SKILL.md) for a <b>structured</b> set of proposed
 * clarifications. It renders a human summary and replies with a {@code pendingAction} so the
 * orchestrator locks the conversation to tasks. On the user's reply {@link #resume} applies each
 * proposal via {@link ClarifyClient} ({@code /internal/clarify}) when affirmative, else cancels.
 *
 * <p>Failures degrade to a friendly Russian message with no pendingAction (so no lock).
 */
@Component
public class InboxClarifier {

    private static final Logger log = LoggerFactory.getLogger(InboxClarifier.class);
    public static final String SKILL_NAME = "inbox-clarify";
    /** pendingAction discriminator the tasks ResumeController dispatches on. */
    public static final String FLOW = "inbox-clarify-apply";
    private static final Set<String> AFFIRMATIVE = Set.of(
            "да", "ага", "верно", "применяй", "применить", "ок", "окей", "давай", "+",
            "yes", "y", "ok", "apply", "confirm");

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final TaskReviewClient review;
    private final ClarifyClient clarify;
    private final ObjectMapper json;

    public InboxClarifier(LlmClient llm,
                          AgentManifest manifest,
                          SkillRegistry skills,
                          TaskReviewClient review,
                          ClarifyClient clarify,
                          ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.review = review;
        this.clarify = clarify;
        this.json = json;
    }

    public Mono<IntentResponse> clarify(NormalizedMessage message) {
        if (message.householdId() == null) {
            return Mono.just(reply("Не знаю, к какому хозяйству относится запрос.", null));
        }
        Skill skill = skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .findFirst()
                .orElse(null);
        if (skill == null) {
            log.warn("inbox-clarify skill not loaded — cannot run the flow");
            return Mono.just(reply("Навык разбора инбокса сейчас недоступен.", null));
        }
        return review.fetch(message.householdId())
                .flatMap(result -> {
                    List<TaskItemDto> inbox = result.inbox();
                    if (inbox == null || inbox.isEmpty()) {
                        return Mono.just(reply("Инбокс пуст — разбирать нечего.", null));
                    }
                    return llm.chat(buildRequest(skill, inbox, message.text()))
                            .map(r -> propose(r.content(), r.model()));
                })
                .onErrorResume(e -> {
                    log.warn("inbox-clarify failed for household {}: {}",
                            message.householdId(), e.toString());
                    return Mono.just(reply(
                            "Не удалось получить инбокс для разбора, попробуйте позже.", null));
                });
    }

    /** Parse the structured proposals, render a human summary, and stash them as a pendingAction. */
    private IntentResponse propose(String llmContent, String model) {
        JsonNode proposals = parseProposals(llmContent);
        if (proposals == null || proposals.isEmpty()) {
            return reply("Не нашёл, что разобрать в инбоксе.", model);
        }
        StringBuilder sb = new StringBuilder("Предлагаю разобрать инбокс:\n");
        for (JsonNode p : proposals) {
            String title = p.path("title").asText("(без названия)");
            String status = p.path("status").asText("next");
            String context = p.path("context").asText("");
            sb.append("• ").append(title).append(" → ").append(status);
            if (!context.isBlank()) sb.append(" ").append(context);
            sb.append('\n');
        }
        sb.append("Применить? Ответьте «да».");
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", FLOW);
        pending.set("proposals", proposals);
        return new IntentResponse(manifest.name(), sb.toString(), model, pending);
    }

    /**
     * Resume after the user replies. Affirmative → apply every proposal via {@code /internal/clarify}
     * (per-item soft-fail so one bad row doesn't abort the rest); anything else → cancel. Either
     * reply carries no pendingAction → the orchestrator clears the lock.
     */
    public Mono<IntentResponse> resume(ResumeRequest req) {
        JsonNode pending = req.pendingAction();
        JsonNode proposals = pending == null ? null : pending.get("proposals");
        if (proposals == null || !proposals.isArray() || proposals.isEmpty()) {
            return Mono.just(reply("Нечего применять — запустите разбор инбокса заново.", null));
        }
        String text = req.message() == null ? null : req.message().text();
        if (!isAffirmative(text)) {
            return Mono.just(reply("Отменил — ничего не менял в инбоксе.", null));
        }
        int total = proposals.size();
        return Flux.fromIterable(proposals)
                .concatMap(this::applyOne)
                .reduce(0, Integer::sum)
                .map(applied -> reply("Разобрал " + applied + " из " + total + " задач.", null));
    }

    private Mono<Integer> applyOne(JsonNode p) {
        ClarifyTaskInput input = toClarifyInput(p);
        if (input == null) {
            return Mono.just(0);
        }
        return clarify.clarify(input)
                .thenReturn(1)
                .onErrorResume(e -> {
                    log.warn("inbox-clarify apply failed for task {}: {}", input.id(), e.toString());
                    return Mono.just(0);
                });
    }

    private ClarifyTaskInput toClarifyInput(JsonNode p) {
        UUID taskId = parseUuid(p.path("taskId").asText(null));
        if (taskId == null) {
            return null;
        }
        String status = p.path("status").asText(null);
        String context = blankToNull(p.path("context").asText(null));
        UUID projectId = parseUuid(p.path("projectId").asText(null));
        return new ClarifyTaskInput(taskId, status, context, projectId, null, null, null);
    }

    private JsonNode parseProposals(String content) {
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
            JsonNode proposals = node.get("proposals");
            return (proposals != null && proposals.isArray()) ? proposals : null;
        } catch (Exception e) {
            return null;
        }
    }

    private LlmChatRequest buildRequest(Skill skill, List<TaskItemDto> inbox, String userText) {
        ObjectNode payload = json.createObjectNode();
        payload.set("inbox", json.valueToTree(inbox));
        if (userText != null && !userText.isBlank()) {
            payload.put("userText", userText);
        }
        return LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skill.body()),
                LlmMessage.user(payload.toString())));
    }

    private static boolean isAffirmative(String text) {
        return text != null && AFFIRMATIVE.contains(text.trim().toLowerCase());
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
