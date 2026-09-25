package dev.fedorov.ailife.agents.inventory.find;

import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * "The thing" out of a sentence about a thing — the search phrase the trigram store is asked for.
 *
 * <p>Why it exists as its own component: stored item names come from <b>photo captions</b> ("ёлочная
 * гирлянда"), so searching the owner's sentence verbatim drags interrogatives and verbs ("где", "лежат",
 * "убери оттуда") into a match against names that contain none of them. Distilling first is what makes
 * the search land at all — and both readers of the item store need it, so it lives here rather than
 * inside one of them ({@link ItemFinder} asks where a thing is; the remover asks which thing to delete).
 *
 * <p>Uses the {@code item-finder} SKILL (strict JSON, temperature 0) and <b>never fails</b>: a useless
 * model reply degrades to the raw text, because a diluted search still beats no search.
 */
@Component
public class ItemQuery {

    private static final String SKILL_NAME = "item-finder";

    private static final Logger log = LoggerFactory.getLogger(ItemQuery.class);

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final ObjectMapper json;

    public ItemQuery(LlmClient llm, SkillRegistry skills, ObjectMapper json) {
        this.llm = llm;
        this.skills = skills;
        this.json = json;
    }

    /** The distilled search phrase; the raw text when the model gives nothing usable. */
    public Mono<String> distil(NormalizedMessage msg) {
        String raw = msg == null || msg.text() == null ? "" : msg.text().trim();
        if (raw.isBlank()) {
            return Mono.just(raw);
        }
        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(skillBody()),
                LlmMessage.user(raw)), 0.0);
        return llm.chat(request)
                .map(r -> {
                    JsonNode draft = parse(r.content());
                    String query = draft == null ? null : draft.path("query").asString(null);
                    return (query == null || query.isBlank()) ? raw : query.trim();
                })
                .onErrorResume(e -> {
                    log.debug("query distil failed, searching the raw text: {}", e.toString());
                    return Mono.just(raw);
                });
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        SKILL_NAME + " SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private JsonNode parse(String content) {
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
}
