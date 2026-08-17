package dev.fedorov.ailife.agents.creator.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.creator.chat.CreatorChat;
import dev.fedorov.ailife.agents.creator.flow.ContentStrategist;
import dev.fedorov.ailife.agents.creator.profile.CreatorProfiler;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Routes a message the orchestrator sent to {@code creator} into one of the agent's flows — set the
 * creator profile ({@link CreatorProfiler}) or produce a content plan ({@link ContentStrategist}) — or a
 * plain chat reply ({@link CreatorChat}).
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, skills-vs-flows
 * Bucket 1 / #475): it supplies the creator-specific parts — the ordered {@code {skillName → flow}}
 * dispatch map, the {@link CreatorChat} fallback, and the intro/decide framing — and the shared router
 * owns the LLM round-trip, the {@link SkillClassifier} parse, and the soft-fail-to-chat dispatch.
 *
 * <p>Only the two <b>user-routable</b> intent skills are in the dispatch map — {@code creator-profiler}
 * and {@code content-strategist}. {@code greeting-drafter} is a loaded skill with empty {@code triggers}
 * but is <b>hub-invoked</b> (the calendar birthday wake calls it over the orchestrator, CR-g), not a user
 * intent, so leaving it out of the map excludes it from both the advertised route set and dispatch — the
 * router keys its route set off the map, so no extra plumbing is needed. Creator binds no MCP tools.
 */
@Component
public class CreatorIntentRouter {

    private static final String CREATOR_PROFILER = "creator-profiler";
    private static final String CONTENT_STRATEGIST = "content-strategist";

    private final SkillRouter router;

    public CreatorIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                               AgentManifest manifest, CreatorProfiler creatorProfiler,
                               ContentStrategist strategist, CreatorChat chat) {
        // greeting-drafter is hub-invoked → deliberately NOT in the map (not advertised / not dispatched).
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(CREATOR_PROFILER, creatorProfiler::setProfile);
        flows.put(CONTENT_STRATEGIST, strategist::run);
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the creator agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (set their creator profile / build a content plan) "
                        + "or just talk?",
                flows, chat::reply);
    }

    public Mono<IntentResponse> route(NormalizedMessage msg) {
        return router.route(msg);
    }

    /** The exact classifier prompt {@link #route} builds — replayed by the routing golden. */
    String buildClassifierPrompt() {
        return router.buildClassifierPrompt();
    }
}
