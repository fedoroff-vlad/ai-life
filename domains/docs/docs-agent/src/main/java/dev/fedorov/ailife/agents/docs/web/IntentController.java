package dev.fedorov.ailife.agents.docs.web;

import dev.fedorov.ailife.agents.docs.archive.DocArchiver;
import dev.fedorov.ailife.agents.docs.chat.DocsChat;
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
 * Hit by the orchestrator when intent routing selects {@code docs}:
 * <ul>
 *   <li>a message carrying a document photo (an {@code image} attachment) → {@link DocArchiver#archive}
 *       (OCR → metadata extract → archive, D-c);</li>
 *   <li>otherwise → the {@link DocsChat} fallback (plain questions; "find my X" search arrives in D-d).</li>
 * </ul>
 * A {@code file} attachment (PDF/scan) is deferred — {@code mcp-media-processing.ocr} decodes a single
 * image today (see plans/docs.md "Deferred"); for now a non-image attachment falls through to chat.
 */
@RestController
@RequestMapping("/agents/docs")
public class IntentController {

    private final DocArchiver archiver;
    private final DocsChat chat;

    public IntentController(DocArchiver archiver, DocsChat chat) {
        this.archiver = archiver;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> image = imageAttachment(message);
        if (image.isPresent()) {
            return archiver.archive(message, image.get().storageUri());
        }
        return chat.reply(message);
    }

    private static Optional<Attachment> imageAttachment(NormalizedMessage message) {
        if (message == null || message.attachments() == null) {
            return Optional.empty();
        }
        return message.attachments().stream()
                .filter(a -> "image".equals(a.kind()) && a.storageUri() != null)
                .findFirst();
    }
}
