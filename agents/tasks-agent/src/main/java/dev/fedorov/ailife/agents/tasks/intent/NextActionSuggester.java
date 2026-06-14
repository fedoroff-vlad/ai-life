package dev.fedorov.ailife.agents.tasks.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.NextActionClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.tasks.TaskItemDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Runs the reactive {@code next-action-suggester} skill: when {@link IntentRouter} classifies a
 * user message as "what should I do now / next actions", this fetches the household's open
 * next-actions (status=next via mcp-tasks' {@code /internal/tasks} passthrough) and asks the LLM —
 * primed with AGENT.md + the {@code next-action-suggester} SKILL.md — to rank and present them by
 * due date, priority and context. Read-only; suggests, doesn't change anything. Mirrors
 * {@link InboxClarifier}.
 *
 * <p>Every failure degrades to a friendly Russian message (this is the user-facing intent path,
 * not a scheduler retry).
 */
@Component
public class NextActionSuggester {

    private static final Logger log = LoggerFactory.getLogger(NextActionSuggester.class);
    public static final String SKILL_NAME = "next-action-suggester";
    private static final int FETCH_LIMIT = 50;

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final NextActionClient nextActions;
    private final ObjectMapper json;

    public NextActionSuggester(LlmClient llm,
                               AgentManifest manifest,
                               SkillRegistry skills,
                               NextActionClient nextActions,
                               ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.nextActions = nextActions;
        this.json = json;
    }

    public Mono<IntentResponse> suggest(NormalizedMessage message) {
        if (message.householdId() == null) {
            return Mono.just(reply("Не знаю, к какому хозяйству относится запрос.", null));
        }
        Skill skill = skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .findFirst()
                .orElse(null);
        if (skill == null) {
            log.warn("next-action-suggester skill not loaded — cannot run the flow");
            return Mono.just(reply("Навык подсказки задач сейчас недоступен.", null));
        }
        return nextActions.fetchNextActions(message.householdId(), FETCH_LIMIT)
                .flatMap(items -> {
                    if (items == null || items.isEmpty()) {
                        return Mono.just(reply(
                                "Нет открытых next-action — всё разобрано или инбокс ждёт разбора.",
                                null));
                    }
                    return llm.chat(buildRequest(skill, items, message.text()))
                            .map(r -> reply(r.content(), r.model()));
                })
                .onErrorResume(e -> {
                    log.warn("next-action-suggester failed for household {}: {}",
                            message.householdId(), e.toString());
                    return Mono.just(reply(
                            "Не удалось получить список задач, попробуйте позже.", null));
                });
    }

    private LlmChatRequest buildRequest(Skill skill, List<TaskItemDto> items, String userText) {
        ObjectNode payload = json.createObjectNode();
        payload.set("nextActions", json.valueToTree(items));
        if (userText != null && !userText.isBlank()) {
            payload.put("userText", userText);
        }
        return LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skill.body()),
                LlmMessage.user(payload.toString())));
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
