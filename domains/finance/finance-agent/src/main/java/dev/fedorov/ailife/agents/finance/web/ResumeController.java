package dev.fedorov.ailife.agents.finance.web;

import dev.fedorov.ailife.agents.finance.account.AccountManager;
import dev.fedorov.ailife.agents.finance.intent.TransactionDeleter;
import dev.fedorov.ailife.agents.finance.intent.TransactionEditor;
import dev.fedorov.ailife.agents.finance.receipt.ReceiptParser;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.sharing.SharingConfirm;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by orchestrator when the user replies to a finance question the agent left open — the
 * conversation was route-locked to {@code finance} (Stage 4 / A4). Dispatches on the
 * {@code pendingAction.flow} discriminator: {@code receipt-confirm} ({@link ReceiptParser#resume}),
 * {@code transaction-delete-confirm} ({@link TransactionDeleter#resume} — confirm-before-delete, #486/Track
 * H.2), {@code transaction-edit-confirm} ({@link TransactionEditor#resume} — confirm-before-change edit,
 * #486/Track H.2) or {@code sharing-confirm} (ADR-0002 item 8 DS-N — the reusable {@link SharingConfirm} confirm loop,
 * finishing a deferred {@link AccountManager} create into the household the owner just chose). The
 * reply's {@code pendingAction} being null clears the lock.
 */
@RestController
@RequestMapping("/agents/finance")
public class ResumeController {

    private final ReceiptParser receiptParser;
    private final AccountManager accountManager;
    private final TransactionDeleter transactionDeleter;
    private final TransactionEditor transactionEditor;
    private final SharingConfirm sharingConfirm;
    private final AgentManifest manifest;

    public ResumeController(ReceiptParser receiptParser, AccountManager accountManager,
                           TransactionDeleter transactionDeleter, TransactionEditor transactionEditor,
                           SharingConfirm sharingConfirm, AgentManifest manifest) {
        this.receiptParser = receiptParser;
        this.accountManager = accountManager;
        this.transactionDeleter = transactionDeleter;
        this.transactionEditor = transactionEditor;
        this.sharingConfirm = sharingConfirm;
        this.manifest = manifest;
    }

    @PostMapping("/resume")
    public Mono<IntentResponse> resume(@RequestBody ResumeRequest request) {
        String flow = request.pendingAction() == null ? null
                : request.pendingAction().path("flow").asString(null);
        if (ReceiptParser.FLOW.equals(flow)) {
            return receiptParser.resume(request);
        }
        if (TransactionDeleter.FLOW.equals(flow)) {
            return transactionDeleter.resume(request);
        }
        if (TransactionEditor.FLOW.equals(flow)) {
            return transactionEditor.resume(request);
        }
        if (SharingConfirm.FLOW.equals(flow)) {
            String reply = request.message() == null ? null : request.message().text();
            return sharingConfirm.resume(request.pendingAction(), reply, accountManager::finishAccount)
                    .map(r -> new IntentResponse(manifest.name(), r.text(), null, r.keepPending()));
        }
        return Mono.just(new IntentResponse(manifest.name(),
                "Не понял, что подтвердить. Повторите запрос, пожалуйста.", null));
    }
}
