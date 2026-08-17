package dev.fedorov.ailife.agents.creator.web;

import dev.fedorov.ailife.agents.creator.intent.CreatorIntentRouter;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code creator}. Delegates to
 * {@link CreatorIntentRouter}, which classifies the message (via the shared {@code SkillClassifier}) into
 * the creator-profile flow, the content-plan flow, or a plain chat reply. The keyword-cue heuristic this
 * controller used to carry was replaced by the LLM classifier in skills-vs-flows Bucket 1 (#475), so the
 * routing SSOT is now each intent skill's SKILL.md description.
 */
@RestController
@RequestMapping("/agents/creator")
public class IntentController {

    private final CreatorIntentRouter router;

    public IntentController(CreatorIntentRouter router) {
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return router.route(message);
    }
}
