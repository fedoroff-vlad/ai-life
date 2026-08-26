package dev.fedorov.ailife.agents.notes.web;

import dev.fedorov.ailife.agents.notes.approve.AmbientApprover;
import dev.fedorov.ailife.agents.notes.intent.FactForgetter;
import dev.fedorov.ailife.agents.notes.intent.NoteDeleter;
import dev.fedorov.ailife.agents.notes.intent.NoteEditor;
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
 * discriminator: {@code ambient-approve} ({@link AmbientApprover#resume}) — the "заметил: … — записать?"
 * confirmation — {@code note-delete-confirm} ({@link NoteDeleter#resume}) — confirm-before-delete — or
 * {@code note-edit-confirm} ({@link NoteEditor#resume}) — confirm-before-change (both #486/Track H.2) — or
 * {@code fact-forget-confirm} ({@link FactForgetter#resume}) — confirm-before-forget/correct a remembered
 * fact (MQ-2, road-test #488). A
 * reply without a recognised flow (or a cleared pendingAction) resolves with no
 * pendingAction, so the orchestrator clears the lock.
 */
@RestController
@RequestMapping("/agents/notes")
public class ResumeController {

    private final AmbientApprover approver;
    private final NoteDeleter deleter;
    private final NoteEditor editor;
    private final FactForgetter forgetter;
    private final AgentManifest manifest;

    public ResumeController(AmbientApprover approver, NoteDeleter deleter, NoteEditor editor,
                            FactForgetter forgetter, AgentManifest manifest) {
        this.approver = approver;
        this.deleter = deleter;
        this.editor = editor;
        this.forgetter = forgetter;
        this.manifest = manifest;
    }

    @PostMapping("/resume")
    public Mono<IntentResponse> resume(@RequestBody ResumeRequest request) {
        String flow = request.pendingAction() == null ? null
                : request.pendingAction().path("flow").asString(null);
        if (AmbientApprover.FLOW.equals(flow)) {
            return approver.resume(request);
        }
        if (NoteDeleter.FLOW.equals(flow)) {
            return deleter.resume(request);
        }
        if (NoteEditor.FLOW.equals(flow)) {
            return editor.resume(request);
        }
        if (FactForgetter.FLOW.equals(flow)) {
            return forgetter.resume(request);
        }
        return Mono.just(new IntentResponse(manifest.name(),
                "Не понял, что подтвердить. Повторите запрос, пожалуйста.", null));
    }
}
