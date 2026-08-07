package dev.fedorov.ailife.agents.docs.web;

import dev.fedorov.ailife.agents.docs.archive.DocArchiver;
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
 * Hit by orchestrator when the user replies to an open docs question — the conversation was route-locked
 * to {@code docs} (Stage 4 / A4). Dispatches on the {@code pendingAction.flow} discriminator; today the
 * only flow is {@code sharing-confirm} (ADR-0002 item 8 DS-N — the reusable {@link SharingConfirm} confirm
 * loop, finishing a deferred {@link DocArchiver} archive into the household the owner just chose after an
 * ambiguous document type). The reply's {@code pendingAction} being null clears the lock.
 *
 * <p>This is docs' first {@code /resume} surface — the archive path was previously non-deferring; DS-N adds
 * the ask-on-ambiguity branch, so the agent now needs a resume endpoint like tasks / finance.
 */
@RestController
@RequestMapping("/agents/docs")
public class ResumeController {

    private final DocArchiver archiver;
    private final SharingConfirm sharingConfirm;
    private final AgentManifest manifest;

    public ResumeController(DocArchiver archiver, SharingConfirm sharingConfirm, AgentManifest manifest) {
        this.archiver = archiver;
        this.sharingConfirm = sharingConfirm;
        this.manifest = manifest;
    }

    @PostMapping("/resume")
    public Mono<IntentResponse> resume(@RequestBody ResumeRequest request) {
        String flow = request.pendingAction() == null ? null
                : request.pendingAction().path("flow").asString(null);
        if (SharingConfirm.FLOW.equals(flow)) {
            String reply = request.message() == null ? null : request.message().text();
            return sharingConfirm.resume(request.pendingAction(), reply, archiver::finishArchive)
                    .map(r -> new IntentResponse(manifest.name(), r.text(), null, r.keepPending()));
        }
        return Mono.just(new IntentResponse(manifest.name(),
                "Не понял, что подтвердить. Повторите запрос, пожалуйста.", null));
    }
}
