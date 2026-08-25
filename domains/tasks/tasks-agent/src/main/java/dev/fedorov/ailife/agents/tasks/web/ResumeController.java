package dev.fedorov.ailife.agents.tasks.web;

import dev.fedorov.ailife.agents.tasks.capture.TaskCapturer;
import dev.fedorov.ailife.agents.tasks.intent.InboxClarifier;
import dev.fedorov.ailife.agents.tasks.intent.TaskDeleter;
import dev.fedorov.ailife.agents.tasks.intent.TaskEditor;
import dev.fedorov.ailife.agents.tasks.intent.TaskStatusMover;
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
 * Hit by orchestrator when the user replies to an open tasks question (the conversation was
 * route-locked to {@code tasks}; Stage 4 / A4). Dispatches on the {@code pendingAction.flow}
 * discriminator: {@code inbox-clarify-apply} ({@link InboxClarifier#resume}), {@code task-delete-confirm}
 * ({@link TaskDeleter#resume} — the confirm-before-delete gate, #486/Track H.2), {@code task-edit-confirm}
 * ({@link TaskEditor#resume} — the confirm-before-change edit gate, #486/Track H.2), {@code task-status-confirm}
 * ({@link TaskStatusMover#resume} — the GTD state-move gate, #486/Track H.2), or {@code sharing-confirm}
 * (ADR-0002 item 8 DS-N — the reusable {@link SharingConfirm} confirm loop, finishing a deferred
 * {@link TaskCapturer} capture into the household the owner just chose). A null {@code pendingAction}
 * on the reply clears the lock.
 */
@RestController
@RequestMapping("/agents/tasks")
public class ResumeController {

    private final InboxClarifier inboxClarifier;
    private final TaskCapturer taskCapturer;
    private final TaskDeleter taskDeleter;
    private final TaskEditor taskEditor;
    private final TaskStatusMover taskStatusMover;
    private final SharingConfirm sharingConfirm;
    private final AgentManifest manifest;

    public ResumeController(InboxClarifier inboxClarifier, TaskCapturer taskCapturer,
                           TaskDeleter taskDeleter, TaskEditor taskEditor, TaskStatusMover taskStatusMover,
                           SharingConfirm sharingConfirm, AgentManifest manifest) {
        this.inboxClarifier = inboxClarifier;
        this.taskCapturer = taskCapturer;
        this.taskDeleter = taskDeleter;
        this.taskEditor = taskEditor;
        this.taskStatusMover = taskStatusMover;
        this.sharingConfirm = sharingConfirm;
        this.manifest = manifest;
    }

    @PostMapping("/resume")
    public Mono<IntentResponse> resume(@RequestBody ResumeRequest request) {
        String flow = request.pendingAction() == null ? null
                : request.pendingAction().path("flow").asString(null);
        if (InboxClarifier.FLOW.equals(flow)) {
            return inboxClarifier.resume(request);
        }
        if (TaskDeleter.FLOW.equals(flow)) {
            return taskDeleter.resume(request);
        }
        if (TaskEditor.FLOW.equals(flow)) {
            return taskEditor.resume(request);
        }
        if (TaskStatusMover.FLOW.equals(flow)) {
            return taskStatusMover.resume(request);
        }
        if (SharingConfirm.FLOW.equals(flow)) {
            String reply = request.message() == null ? null : request.message().text();
            return sharingConfirm.resume(request.pendingAction(), reply, taskCapturer::finishCapture)
                    .map(r -> new IntentResponse(manifest.name(), r.text(), null, r.keepPending()));
        }
        return Mono.just(new IntentResponse(manifest.name(),
                "Не понял, что подтвердить. Повторите запрос, пожалуйста.", null));
    }
}
