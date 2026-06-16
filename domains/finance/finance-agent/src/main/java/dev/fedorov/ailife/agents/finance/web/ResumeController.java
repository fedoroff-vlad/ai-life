package dev.fedorov.ailife.agents.finance.web;

import dev.fedorov.ailife.agents.finance.receipt.ReceiptParser;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by orchestrator when the user replies to a finance question the agent left open — the
 * conversation was route-locked to {@code finance} (Stage 4 / A4). Dispatches on the
 * {@code pendingAction.flow} discriminator; today the only flow is {@code receipt-confirm}
 * ({@link ReceiptParser#resume}). The reply's {@code pendingAction} being null clears the lock.
 */
@RestController
@RequestMapping("/agents/finance")
public class ResumeController {

    private final ReceiptParser receiptParser;
    private final AgentManifest manifest;

    public ResumeController(ReceiptParser receiptParser, AgentManifest manifest) {
        this.receiptParser = receiptParser;
        this.manifest = manifest;
    }

    @PostMapping("/resume")
    public Mono<IntentResponse> resume(@RequestBody ResumeRequest request) {
        String flow = request.pendingAction() == null ? null
                : request.pendingAction().path("flow").asText(null);
        if (ReceiptParser.FLOW.equals(flow)) {
            return receiptParser.resume(request);
        }
        return Mono.just(new IntentResponse(manifest.name(),
                "Не понял, что подтвердить. Повторите запрос, пожалуйста.", null));
    }
}
