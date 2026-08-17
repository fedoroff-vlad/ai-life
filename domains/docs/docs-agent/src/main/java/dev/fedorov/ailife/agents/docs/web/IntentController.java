package dev.fedorov.ailife.agents.docs.web;

import dev.fedorov.ailife.agents.docs.archive.DocArchiver;
import dev.fedorov.ailife.agents.docs.intent.DocsIntentRouter;
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
 *   <li>otherwise → {@link DocsIntentRouter}, which classifies the text (via the shared
 *       {@code SkillClassifier}) into the "find my X" flow or a plain chat reply.</li>
 * </ul>
 * The photo check comes first (a photo is unambiguously an ingest, not a text intent, so it stays a
 * deterministic pre-check rather than an LLM classification). The find-vs-chat keyword-cue heuristic this
 * controller used to carry was replaced by the LLM classifier in skills-vs-flows Bucket 1 (#475), so the
 * routing SSOT is the {@code doc-finder} SKILL.md description; the family/own read-scope stays a
 * deterministic cue inside the finder flow ({@link DocsIntentRouter}). A {@code file} attachment (PDF/scan)
 * is deferred — {@code mcp-media-processing.ocr} decodes a single image today (see plans/docs.md
 * "Deferred"); a non-image attachment falls through to the classifier / chat.
 */
@RestController
@RequestMapping("/agents/docs")
public class IntentController {

    private final DocArchiver archiver;
    private final DocsIntentRouter router;

    public IntentController(DocArchiver archiver, DocsIntentRouter router) {
        this.archiver = archiver;
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> image = imageAttachment(message);
        if (image.isPresent()) {
            return archiver.archive(message, image.get().storageUri());
        }
        return router.route(message);
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
