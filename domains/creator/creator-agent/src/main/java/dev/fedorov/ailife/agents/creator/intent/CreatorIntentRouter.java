package dev.fedorov.ailife.agents.creator.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.Choice;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.creator.chat.CreatorChat;
import dev.fedorov.ailife.agents.creator.flow.ContentStrategist;
import dev.fedorov.ailife.agents.creator.profile.CreatorProfiler;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes a message the orchestrator sent to {@code creator} into one of the agent's flows — set the
 * creator profile ({@link CreatorProfiler}) or produce a content plan ({@link ContentStrategist}) — or a
 * plain chat reply ({@link CreatorChat}).
 *
 * <p>Replaces the previous hardcoded {@code PROFILE_CUES}/{@code STRATEGIST_CUES} keyword heuristic with the
 * shared {@link SkillClassifier} ({@code libs/agent-runtime}, skills-vs-flows Bucket 1 — the LLM-classified
 * path finance/tasks/notes use; #475). Creator binds no directly-routable MCP tools, so the classifier
 * chooses between {@code skill} and {@code chat} only.
 *
 * <p>Only the two <b>user-routable</b> intent skills are advertised — {@code creator-profiler} and
 * {@code content-strategist}. {@code greeting-drafter} is a loaded skill with empty {@code triggers} but is
 * <b>hub-invoked</b> (the calendar birthday wake calls it over the orchestrator, CR-g), not a user intent,
 * so it is deliberately excluded from the route set (the generic "no triggers = intent skill" filter would
 * otherwise advertise it). Every stage soft-fails to {@link CreatorChat}.
 */
@Component
public class CreatorIntentRouter {

    private static final Logger log = LoggerFactory.getLogger(CreatorIntentRouter.class);

    private static final String SKILL_ACTION = "skill";
    private static final String CREATOR_PROFILER = "creator-profiler";
    private static final String CONTENT_STRATEGIST = "content-strategist";
    /** The user-routable intent skills (greeting-drafter is hub-invoked → not advertised/dispatched). */
    private static final List<String> ROUTABLE = List.of(CREATOR_PROFILER, CONTENT_STRATEGIST);

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final SkillClassifier classifier;
    private final AgentManifest manifest;
    private final CreatorProfiler creatorProfiler;
    private final ContentStrategist strategist;
    private final CreatorChat chat;

    public CreatorIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                               AgentManifest manifest, CreatorProfiler creatorProfiler,
                               ContentStrategist strategist, CreatorChat chat) {
        this.llm = llm;
        this.skills = skills;
        this.classifier = classifier;
        this.manifest = manifest;
        this.creatorProfiler = creatorProfiler;
        this.strategist = strategist;
        this.chat = chat;
    }

    public Mono<IntentResponse> route(NormalizedMessage msg) {
        String text = msg == null ? null : msg.text();
        if (text == null || text.isBlank()) {
            return chat.reply(msg);
        }
        List<Choice> choices = choices();
        if (choices.isEmpty()) {
            return chat.reply(msg);                       // skills not loaded → plain chat (back-compat)
        }
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.system(buildClassifierPrompt()),
                LlmMessage.user(text)));
        return llm.chat(req)
                .flatMap(resp -> {
                    String raw = resp.content() == null ? "" : resp.content().trim();
                    return switch (classifier.parse(raw, List.of(), choices)) {
                        case SkillClassifier.FlowCall f -> dispatch(f, msg);
                        case SkillClassifier.Chat c -> chat.reply(msg);
                        case SkillClassifier.ToolCall t -> chat.reply(msg);   // no MCP tools
                    };
                })
                .onErrorResume(e -> {
                    log.warn("creator routing failed: {}", e.toString());
                    return chat.reply(msg);
                });
    }

    private Mono<IntentResponse> dispatch(SkillClassifier.FlowCall f, NormalizedMessage msg) {
        String name = f.node().path("name").asString(null);
        if (name == null) {
            return chat.reply(msg);
        }
        return switch (name) {
            case CREATOR_PROFILER -> creatorProfiler.setProfile(msg);
            case CONTENT_STRATEGIST -> strategist.run(msg);
            default -> {
                log.warn("LLM routed creator to unknown/hub skill '{}' — chat fallback", name);
                yield chat.reply(msg);
            }
        };
    }

    /** The routable intent skills as one {@code skill} choice; descriptions come from their SKILL.md. */
    private List<Choice> choices() {
        List<Skill> routable = new ArrayList<>();
        for (String name : ROUTABLE) {
            skills.all().stream().filter(s -> name.equals(s.name())).findFirst().ifPresent(routable::add);
        }
        if (routable.isEmpty()) {
            return List.of();
        }
        StringBuilder block = new StringBuilder(
                "Available skills (multi-step flows — pick one only when the user clearly asks for it):");
        for (Skill s : routable) {
            block.append("\n- ").append(s.name()).append(": ").append(s.description());
        }
        return List.of(new Choice(SKILL_ACTION, block.toString(),
                "{\"action\":\"skill\",\"name\":\"<skill-name>\"}"));
    }

    /** The exact classifier prompt {@link #route} builds — replayed by the routing golden. */
    String buildClassifierPrompt() {
        return classifier.buildPrompt(
                "You are routing a message for the creator agent. Reply directly to the user, or run one skill.",
                List.of(),                                // no MCP tools
                choices(),
                "Decide: does the user want to run a skill (set their creator profile / build a content plan) "
                        + "or just talk?");
    }
}
