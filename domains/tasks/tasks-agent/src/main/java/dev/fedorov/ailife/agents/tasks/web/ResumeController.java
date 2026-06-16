package dev.fedorov.ailife.agents.tasks.web;

import dev.fedorov.ailife.agents.tasks.intent.InboxClarifier;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by orchestrator when the user replies to an open tasks question (the conversation was
 * route-locked to {@code tasks}; Stage 4 / A4). Dispatches on the {@code pendingAction.flow}
 * discriminator; today the only flow is {@code inbox-clarify-apply} ({@link InboxClarifier#resume}).
 * A null {@code pendingAction} on the reply clears the lock.
 */
@RestController
@RequestMapping("/agents/tasks")
public class ResumeController {

    private final InboxClarifier inboxClarifier;
    private final AgentManifest manifest;

    public ResumeController(InboxClarifier inboxClarifier, AgentManifest manifest) {
        this.inboxClarifier = inboxClarifier;
        this.manifest = manifest;
    }

    @PostMapping("/resume")
    public Mono<IntentResponse> resume(@RequestBody ResumeRequest request) {
        String flow = request.pendingAction() == null ? null
                : request.pendingAction().path("flow").asText(null);
        if (InboxClarifier.FLOW.equals(flow)) {
            return inboxClarifier.resume(request);
        }
        return Mono.just(new IntentResponse(manifest.name(),
                "Не понял, что подтвердить. Повторите запрос, пожалуйста.", null));
    }
}
