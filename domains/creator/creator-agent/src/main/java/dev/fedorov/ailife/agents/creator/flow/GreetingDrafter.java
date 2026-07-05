package dev.fedorov.ailife.agents.creator.flow;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * The {@code draft_greeting} core (CR-g): one LLM turn that drafts a short, warm greeting for a named
 * person and an occasion. The only consumer is the inter-agent action ({@code ActionController}) the
 * calendar birthday wake invokes over the orchestrator hub — closing the Stage-4 chain
 * {@code calendar.birthday_upcoming → creator.draft_greeting → notifier.send}. The synthesis is from
 * {@code [AGENT.md, greeting-drafter SKILL.md] + {payload(person, occasion)}}; no gather, no media —
 * the greeting is plain text the caller delivers. Mirrors the chef's {@code RecipeFinder} as the
 * action-backing flow (here without the web/render steps).
 */
@Component
public class GreetingDrafter {

    private static final String SKILL_NAME = "greeting-drafter";
    private static final String DEFAULT_OCCASION = "birthday";

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public GreetingDrafter(LlmClient llm, SkillRegistry skills, AgentManifest manifest, ObjectMapper json) {
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    /**
     * Draft a greeting for {@code person} on {@code occasion} (defaults to a birthday). Errors
     * propagate — the caller ({@code ActionController}) turns them into a structured
     * {@code AgentActionResult.error} so the inter-agent hop soft-fails.
     */
    public Mono<GreetingDraft> draft(String person, String occasion) {
        String who = person == null ? null : person.strip();
        if (who == null || who.isEmpty()) {
            return Mono.error(new IllegalArgumentException("draft_greeting requires args.person"));
        }
        String forWhat = (occasion == null || occasion.isBlank()) ? DEFAULT_OCCASION : occasion.strip();

        ObjectNode payload = json.createObjectNode();
        payload.put("person", who);
        payload.put("occasion", forWhat);

        LlmChatRequest request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(skillBody()),
                LlmMessage.user(payload.toString())));

        return llm.chat(request).map(r -> new GreetingDraft(r.content(), r.model()));
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "greeting-drafter SKILL.md not loaded — check skills-classpath"));
    }

    /** The drafted greeting {@code text} and the {@code model} that produced it (for provenance). */
    public record GreetingDraft(String text, String model) {
    }
}
