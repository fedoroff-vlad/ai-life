package dev.fedorov.ailife.agents.inventory.web;

import dev.fedorov.ailife.agents.inventory.intent.InventoryIntentRouter;
import dev.fedorov.ailife.agents.inventory.pack.BoxPacker;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Hit by the orchestrator when intent routing selects {@code inventory}.
 *
 * <p>A photo arriving <b>here</b> means no packing session is open — an open one route-locks the
 * conversation, so its photos land on {@code /resume} instead. The agent therefore asks which
 * container it belongs to rather than guessing: a misfiled thing is worse than one extra question,
 * because the owner will look for it in the wrong box months later.
 *
 * <p>Text falls to {@link InventoryIntentRouter}, which classifies it (via the shared
 * {@code SkillClassifier}) into the packing flow or a plain chat reply.
 */
@RestController
@RequestMapping("/agents/inventory")
public class IntentController {

    private final BoxPacker packer;
    private final InventoryIntentRouter router;

    public IntentController(BoxPacker packer, InventoryIntentRouter router) {
        this.packer = packer;
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        if (imageAttachment(message).isPresent()) {
            return packer.photoWithoutSession();
        }
        return router.route(message);
    }

    static Optional<Attachment> imageAttachment(NormalizedMessage message) {
        if (message == null || message.attachments() == null) {
            return Optional.empty();
        }
        return message.attachments().stream()
                .filter(a -> "image".equals(a.kind()) && a.storageUri() != null)
                .findFirst();
    }
}
