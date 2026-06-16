package dev.fedorov.ailife.agents.tasks.web;

import dev.fedorov.ailife.agents.tasks.intent.InboxClarifier;
import dev.fedorov.ailife.agents.tasks.intent.IntentRouter;
import dev.fedorov.ailife.agents.tasks.intent.NextActionSuggester;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by orchestrator when intent routing selects {@code tasks}. Delegates to {@link IntentRouter}:
 * when mcp-tasks tools are wired the router asks the LLM to invoke a tool (capture/clarify/list),
 * run an intent skill, or reply directly; otherwise it falls back to a plain LLM chat (skeleton
 * behaviour). When the router picks an intent skill the controller dispatches to that skill's flow
 * — today only {@code inbox-clarify} ({@link InboxClarifier}). The controller stays thin and wraps
 * the result in an {@link IntentResponse} (propagating the model id, preserving the orchestrator's
 * intent contract). Today the intent skills are {@code inbox-clarify} ({@link InboxClarifier}) and
 * {@code next-action-suggester} ({@link NextActionSuggester}).
 */
@RestController
@RequestMapping("/agents/tasks")
public class IntentController {

    private final IntentRouter router;
    private final AgentManifest manifest;
    private final InboxClarifier inboxClarifier;
    private final NextActionSuggester nextActionSuggester;

    public IntentController(IntentRouter router, AgentManifest manifest,
                           InboxClarifier inboxClarifier, NextActionSuggester nextActionSuggester) {
        this.router = router;
        this.manifest = manifest;
        this.inboxClarifier = inboxClarifier;
        this.nextActionSuggester = nextActionSuggester;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return router.route(message.text())
                .flatMap(r -> {
                    if (InboxClarifier.SKILL_NAME.equals(r.invokedSkill())) {
                        return inboxClarifier.clarify(message);
                    }
                    if (NextActionSuggester.SKILL_NAME.equals(r.invokedSkill())) {
                        return nextActionSuggester.suggest(message);
                    }
                    return Mono.just(new IntentResponse(manifest.name(), r.text(), r.llmModel()));
                });
    }
}
