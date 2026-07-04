package dev.fedorov.ailife.agents.notes.web;

import dev.fedorov.ailife.agents.notes.approve.AmbientApprover;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when the owner replies to an open notes question (the conversation was
 * route-locked to {@code notes}; Stage 4 route-lock + AC-4). Dispatches on the {@code pendingAction.flow}
 * discriminator; today the only flow is {@code ambient-approve} ({@link AmbientApprover#resume}) — the
 * "заметил: … — записать?" confirmation. A reply without a recognised flow (or a cleared pendingAction)
 * resolves with no pendingAction, so the orchestrator clears the lock.
 */
@RestController
@RequestMapping("/agents/notes")
public class ResumeController {

    private final AmbientApprover approver;
    private final AgentManifest manifest;

    public ResumeController(AmbientApprover approver, AgentManifest manifest) {
        this.approver = approver;
        this.manifest = manifest;
    }

    @PostMapping("/resume")
    public Mono<IntentResponse> resume(@RequestBody ResumeRequest request) {
        String flow = request.pendingAction() == null ? null
                : request.pendingAction().path("flow").asText(null);
        if (AmbientApprover.FLOW.equals(flow)) {
            return approver.resume(request);
        }
        return Mono.just(new IntentResponse(manifest.name(),
                "Не понял, что подтвердить. Повторите запрос, пожалуйста.", null));
    }
}
