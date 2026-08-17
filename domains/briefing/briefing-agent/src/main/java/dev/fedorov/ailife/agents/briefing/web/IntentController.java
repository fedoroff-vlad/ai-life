package dev.fedorov.ailife.agents.briefing.web;

import dev.fedorov.ailife.agents.briefing.intent.BriefingIntentRouter;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code briefing}: delegates to
 * {@link BriefingIntentRouter}, which classifies the message (via the shared {@code SkillClassifier}) into
 * one of the two intent flows — produce the morning digest now ({@code briefing-composer}) or set/change
 * the person's briefing preferences ({@code briefing-profiler}) — or a plain chat reply.
 *
 * <p>The deterministic {@code DIGEST_CUES}/{@code PROFILE_CUES} keyword heuristic this controller used to
 * carry was replaced by the LLM classifier in skills-vs-flows Bucket 1 (#475), so the routing SSOT is each
 * intent skill's SKILL.md description. Briefing has no attachment or read-scope pre-check to keep here (a
 * digest/preferences request is always a text intent), so the controller is a thin passthrough.
 */
@RestController
@RequestMapping("/agents/briefing")
public class IntentController {

    private final BriefingIntentRouter router;

    public IntentController(BriefingIntentRouter router) {
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return router.route(message);
    }
}
