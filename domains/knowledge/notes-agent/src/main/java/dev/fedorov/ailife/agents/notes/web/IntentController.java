package dev.fedorov.ailife.agents.notes.web;

import dev.fedorov.ailife.agents.notes.intent.NotesIntentRouter;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code notes}. Delegates to
 * {@link NotesIntentRouter}, which classifies the message (via the shared {@code SkillClassifier}) into
 * one of the agent's flows — recall / list op / capture — or a plain chat reply. The keyword-cue
 * heuristic this controller used to carry was replaced by the LLM classifier in skills-vs-flows Bucket 1
 * (#475), so the routing SSOT is now each intent skill's SKILL.md description.
 */
@RestController
@RequestMapping("/agents/notes")
public class IntentController {

    private final NotesIntentRouter router;

    public IntentController(NotesIntentRouter router) {
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return router.route(message);
    }
}
