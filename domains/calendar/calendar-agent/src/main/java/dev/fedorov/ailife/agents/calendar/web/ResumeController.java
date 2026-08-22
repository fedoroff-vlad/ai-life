package dev.fedorov.ailife.agents.calendar.web;

import dev.fedorov.ailife.agents.calendar.flow.EventCanceller;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by orchestrator when the user replies to a calendar question the agent left open — the conversation
 * was route-locked to {@code calendar} (Stage 4 / A4). Dispatches on the {@code pendingAction.flow}
 * discriminator; the only flow so far is {@code event-cancel-confirm} ({@link EventCanceller#resume}, the
 * confirm-before-delete gate, #486/Track H.2 HC-3). The reply's {@code pendingAction} being null clears the
 * lock.
 */
@RestController
@RequestMapping("/agents/calendar")
public class ResumeController {

    private final EventCanceller canceller;
    private final AgentManifest manifest;

    public ResumeController(EventCanceller canceller, AgentManifest manifest) {
        this.canceller = canceller;
        this.manifest = manifest;
    }

    @PostMapping("/resume")
    public Mono<IntentResponse> resume(@RequestBody ResumeRequest request) {
        String flow = request.pendingAction() == null ? null
                : request.pendingAction().path("flow").asString(null);
        if (EventCanceller.FLOW.equals(flow)) {
            return canceller.resume(request);
        }
        return Mono.just(new IntentResponse(manifest.name(),
                "Не понял, что подтвердить. Повторите запрос, пожалуйста.", null));
    }
}
