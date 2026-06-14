package dev.fedorov.ailife.agents.tasks.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.tasks.http.TaskReviewClient;
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
 * Runs the reactive {@code inbox-clarify} skill: when {@link IntentRouter} classifies a user message
 * as a request to clarify the GTD inbox, this fetches the household's un-clarified inbox items
 * (reusing mcp-tasks' {@code /internal/review} aggregate via {@link TaskReviewClient}) and asks the
 * LLM — primed with AGENT.md + the {@code inbox-clarify} SKILL.md — to propose a GTD clarification
 * for each item.
 *
 * <p><b>Proposal-only MVP.</b> The skill suggests how to organize each item but does NOT apply any
 * {@code clarify_task} call. Apply-on-confirm is deferred with the conversation-state layer (same
 * blocker as the receipt-parser confirm flow — see STATUS Deferred work). Every failure degrades to
 * a friendly Russian message rather than an exception (this is the user-facing intent path, not a
 * scheduler retry).
 */
@Component
public class InboxClarifier {

    private static final Logger log = LoggerFactory.getLogger(InboxClarifier.class);
    public static final String SKILL_NAME = "inbox-clarify";

    private final LlmClient llm;
    private final AgentManifest manifest;
    private final SkillRegistry skills;
    private final TaskReviewClient review;
    private final ObjectMapper json;

    public InboxClarifier(LlmClient llm,
                          AgentManifest manifest,
                          SkillRegistry skills,
                          TaskReviewClient review,
                          ObjectMapper json) {
        this.llm = llm;
        this.manifest = manifest;
        this.skills = skills;
        this.review = review;
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
                            .map(r -> reply(r.content(), r.model()));
                })
                .onErrorResume(e -> {
                    log.warn("inbox-clarify failed for household {}: {}",
                            message.householdId(), e.toString());
                    return Mono.just(reply(
                            "Не удалось получить инбокс для разбора, попробуйте позже.", null));
                });
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

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }
}
