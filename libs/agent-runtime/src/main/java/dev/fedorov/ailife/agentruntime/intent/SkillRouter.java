package dev.fedorov.ailife.agentruntime.intent;

import dev.fedorov.ailife.agentruntime.intent.SkillClassifier.Choice;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The shared <b>skills-only in-agent router</b>: the LLM round-trip + dispatch wrapper around
 * {@link SkillClassifier} for agents whose only routing choice is "run one of my intent skills, or just
 * chat" (no directly-routable MCP tools). Lifted out of the per-agent {@code *IntentRouter}s that
 * {@code notes-agent} and {@code creator-agent} ran near-identically (skills-vs-flows Bucket 1, #475 /
 * epic #479) — the router shape settled over those two consumers (skills-only prompt via #481; an
 * explicit routable set that excludes hub-invoked skills), so it is lifted here before a third copy.
 *
 * <p>The agent supplies only the parts that are genuinely its own, all via the constructor:
 * <ul>
 *   <li>an ordered <b>dispatch map</b> {@code {skillName → flow}} — the routable intent skills and the
 *   flow each runs. Its key set <b>is</b> the route set: a loaded skill absent from the map (e.g.
 *   creator's hub-invoked {@code greeting-drafter}) is neither advertised nor dispatched, so the
 *   "exclude a hub-invoked skill" case needs no extra plumbing;</li>
 *   <li>a <b>chat fallback</b> — the plain conversational reply every soft-fail lands on;</li>
 *   <li>the {@code intro} + {@code decidePrompt} framing lines for {@link SkillClassifier#buildPrompt}.</li>
 * </ul>
 *
 * <p>Every stage soft-fails to the chat fallback: a blank message, no routable skill loaded, an unknown
 * or hub-only skill name, non-routing prose, a {@code tool} decision (skills-only agents dispatch none),
 * or an LLM error — none throw, all reply conversationally (preserving the pre-lift fallback UX). SKILL.md
 * {@code description}s remain the routing SSOT: each routable skill's description (looked up in the
 * {@link SkillRegistry}) is what the classifier sees.
 */
public final class SkillRouter {

    private static final Logger log = LoggerFactory.getLogger(SkillRouter.class);

    /** The single classifier action the intent skills are offered under (one action, many skills). */
    private static final String SKILL_ACTION = "skill";

    private final LlmClient llm;
    private final SkillRegistry skills;
    private final SkillClassifier classifier;
    private final AgentManifest manifest;
    private final String intro;
    private final String decidePrompt;
    /** Ordered {skillName → flow}; the key set is the routable route set (insertion order = prompt order). */
    private final Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows;
    private final Function<NormalizedMessage, Mono<IntentResponse>> chatFallback;

    public SkillRouter(LlmClient llm, SkillRegistry skills, SkillClassifier classifier,
                       AgentManifest manifest, String intro, String decidePrompt,
                       Map<String, Function<NormalizedMessage, Mono<IntentResponse>>> flows,
                       Function<NormalizedMessage, Mono<IntentResponse>> chatFallback) {
        this.llm = llm;
        this.skills = skills;
        this.classifier = classifier;
        this.manifest = manifest;
        this.intro = intro;
        this.decidePrompt = decidePrompt;
        this.flows = new LinkedHashMap<>(flows);          // defensive copy, keep insertion order
        this.chatFallback = chatFallback;
    }

    /** Classify {@code msg} into one routable skill flow, or a plain chat reply. Never throws. */
    public Mono<IntentResponse> route(NormalizedMessage msg) {
        String text = msg == null ? null : msg.text();
        if (text == null || text.isBlank()) {
            return chatFallback.apply(msg);               // the empty-text hint (skeleton back-compat)
        }
        List<Choice> choices = choices();
        if (choices.isEmpty()) {
            return chatFallback.apply(msg);               // no routable skill loaded → plain chat
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
                        case SkillClassifier.Chat c -> chatFallback.apply(msg);
                        // skills-only agent: a tool call can't be produced, but stay total.
                        case SkillClassifier.ToolCall t -> chatFallback.apply(msg);
                    };
                })
                .onErrorResume(e -> {
                    log.warn("{} routing failed: {}", manifest.name(), e.toString());
                    return chatFallback.apply(msg);
                });
    }

    /** Run the flow the LLM named in {@code node.name}; an unknown/hub-only name soft-falls to chat. */
    private Mono<IntentResponse> dispatch(SkillClassifier.FlowCall f, NormalizedMessage msg) {
        String name = f.node().path("name").asString(null);
        Function<NormalizedMessage, Mono<IntentResponse>> flow = name == null ? null : flows.get(name);
        if (flow == null) {
            if (name != null) {
                log.warn("{} routed to unknown/hub skill '{}' — chat fallback", manifest.name(), name);
            }
            return chatFallback.apply(msg);
        }
        return flow.apply(msg);
    }

    /**
     * The routable intent skills collapsed into one {@code skill} choice (the LLM names the skill in
     * {@code node.name}); descriptions come from each skill's SKILL.md via the {@link SkillRegistry}. A
     * dispatch-map key not present in the registry is skipped; if none resolve, an empty list signals
     * {@link #route} to fall straight to chat (back-compat with "skills not loaded").
     */
    private List<Choice> choices() {
        List<Skill> routable = new ArrayList<>();
        for (String name : flows.keySet()) {
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

    /**
     * Build the classifier system prompt via the shared {@link SkillClassifier#buildPrompt} scaffold —
     * skills-only (no MCP tools). Public so a {@code @GoldenLlmTest} can replay the exact prompt against
     * a live model.
     */
    public String buildClassifierPrompt() {
        return classifier.buildPrompt(intro, List.of(), choices(), decidePrompt);
    }
}
