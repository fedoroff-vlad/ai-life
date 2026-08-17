package dev.fedorov.ailife.agents.briefing.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier;
import dev.fedorov.ailife.agentruntime.intent.SkillRouter;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.briefing.chat.BriefingChat;
import dev.fedorov.ailife.agents.briefing.flow.BriefingComposer;
import dev.fedorov.ailife.agents.briefing.profile.BriefingProfiler;
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
 * Routes a message the orchestrator sent to {@code briefing} into one of the agent's two intent flows —
 * produce the morning digest now ({@link BriefingComposer#digest}) or set/change the person's briefing
 * preferences ({@link BriefingProfiler#setProfile}) — or a plain chat reply ({@link BriefingChat}).
 *
 * <p>A thin binding over the shared {@link SkillRouter} ({@code libs/agent-runtime}, skills-vs-flows
 * Bucket 1 / #475): it supplies the briefing-specific parts — the ordered {@code {skillName → flow}}
 * dispatch map ({@code briefing-composer} → digest, {@code briefing-profiler} → preferences), the
 * {@link BriefingChat} fallback, and the intro/decide framing — and the shared router owns the LLM
 * round-trip, the {@link SkillClassifier} parse, and the soft-fail-to-chat dispatch. Each skill's SKILL.md
 * {@code description} is the routing SSOT, so a paraphrase outside the old {@code DIGEST_CUES}/{@code
 * PROFILE_CUES} keyword lists routes correctly. Where the deterministic cues checked the digest cue before
 * the preferences cue, the LLM now disambiguates from the descriptions.
 *
 * <p>Both routable skills carry empty {@code triggers} in their SKILL.md — they are hub/agent-internal by
 * default — but here they are explicitly mapped, so their descriptions form the advertised route set (the
 * router keys its route set off the map). Briefing binds no directly-routable MCP tools.
 */
@Component
public class BriefingIntentRouter {

    private static final String BRIEFING_COMPOSER = "briefing-composer";
    private static final String BRIEFING_PROFILER = "briefing-profiler";

    private final SkillRouter router;

    public BriefingIntentRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                                AgentManifest manifest, BriefingComposer composer,
                                BriefingProfiler profiler, BriefingChat chat) {
        Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows = new LinkedHashMap<>();
        flows.put(BRIEFING_COMPOSER, composer::digest);
        flows.put(BRIEFING_PROFILER, profiler::setProfile);
        this.router = new SkillRouter(llm, skills, classifier, manifest,
                "You are routing a message for the briefing agent. Reply directly to the user, or run one skill.",
                "Decide: does the user want to run a skill (produce their morning digest now / set up or "
                        + "change their briefing preferences) or just talk?",
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
