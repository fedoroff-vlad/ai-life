package dev.fedorov.ailife.agents.inventory.web;

import dev.fedorov.ailife.agents.inventory.pack.BoxPacker;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator while a packing session is open — the conversation is route-locked to
 * {@code inventory} (Stage 4 / A4), so every message, photo or text, comes straight back here with
 * the session envelope. That lock <b>is</b> the packing session: it is why a batch of photos needs no
 * "which box?" per photo.
 *
 * <p>Dispatches on the {@code pendingAction.flow} discriminator; today the only flow is
 * {@code box-packing}. The reply's {@code pendingAction} being null clears the lock (the box closed).
 */
@RestController
@RequestMapping("/agents/inventory")
public class ResumeController {

    private final BoxPacker packer;
    private final AgentManifest manifest;

    public ResumeController(BoxPacker packer, AgentManifest manifest) {
        this.packer = packer;
        this.manifest = manifest;
    }

    @PostMapping("/resume")
    public Mono<IntentResponse> resume(@RequestBody ResumeRequest request) {
        String flow = request.pendingAction() == null ? null
                : request.pendingAction().path("flow").asString(null);
        if (!BoxPacker.FLOW.equals(flow)) {
            return Mono.just(new IntentResponse(manifest.name(),
                    "Не понял, к чему это относится. Повторите запрос, пожалуйста.", null));
        }
        String mediaId = IntentController.imageAttachment(request.message())
                .map(a -> a.storageUri())
                .orElse(null);
        return packer.resume(request.pendingAction(), request.message(), mediaId);
    }
}
