package dev.fedorov.ailife.agents.inventory.web;

import dev.fedorov.ailife.agents.inventory.intent.InventoryIntentRouter;
import dev.fedorov.ailife.agents.inventory.pack.BoxAppender;
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
 * conversation, so its photos land on {@code /resume} instead. Two cases then: a photo <b>with a
 * caption</b> is the owner saying where it goes, so it is filed into the box they named
 * ({@link BoxAppender}, IN-g3, stateless — no session opened for one thing); a <b>bare</b> photo has
 * nothing to go on, so the agent asks which container it belongs to rather than guessing. A misfiled
 * thing is worse than one extra question, because the owner will look for it in the wrong box months
 * later — which is also why an unresolvable caption asks too.
 *
 * <p>Text falls to {@link InventoryIntentRouter}, which classifies it (via the shared
 * {@code SkillClassifier}) into the packing flow or a plain chat reply.
 */
@RestController
@RequestMapping("/agents/inventory")
public class IntentController {

    private final BoxPacker packer;
    private final BoxAppender appender;
    private final InventoryIntentRouter router;

    public IntentController(BoxPacker packer, BoxAppender appender, InventoryIntentRouter router) {
        this.packer = packer;
        this.appender = appender;
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> photo = imageAttachment(message);
        if (photo.isPresent()) {
            // A caption is the owner telling us where this goes (IN-g3) — try to file it into the box
            // they named. Without one there is nothing to go on, so ask rather than guess.
            return hasText(message)
                    ? appender.append(message, photo.get().storageUri())
                    : packer.photoWithoutSession();
        }
        return router.route(message);
    }

    private static boolean hasText(NormalizedMessage message) {
        return message.text() != null && !message.text().isBlank();
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
