package dev.fedorov.ailife.agents.docs.web;

import dev.fedorov.ailife.agents.docs.archive.DocArchiver;
import dev.fedorov.ailife.agents.docs.chat.DocsChat;
import dev.fedorov.ailife.agents.docs.find.DocFinder;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Hit by the orchestrator when intent routing selects {@code docs}:
 * <ul>
 *   <li>a message carrying a document photo (an {@code image} attachment) → {@link DocArchiver#archive}
 *       (OCR → metadata extract → archive, D-c);</li>
 *   <li>a "find my X" cue → {@link DocFinder#find} (distil a query → search the archive, D-d). An
 *       explicit family cue ("наши документы") widens the search to the member's personal ∪ shared
 *       households (ADR-0002 slice 7b); by default it stays the member's own archive.</li>
 *   <li>otherwise → the {@link DocsChat} fallback (plain questions).</li>
 * </ul>
 * The photo check comes first (a photo is unambiguously an ingest). The find-cue split is a
 * deterministic keyword heuristic — good enough for the MVP, MockWebServer-testable, replaceable by an
 * LLM classifier later. The family/own scope is the same deterministic-keyword style (a read-breadth
 * choice, never a privacy write boundary), mirroring nutrition's {@code FAMILY_CUES}. A {@code file} attachment (PDF/scan) is deferred — {@code
 * mcp-media-processing.ocr} decodes a single image today (see plans/docs.md "Deferred"); a non-image
 * attachment falls through to the cue check / chat.
 */
@RestController
@RequestMapping("/agents/docs")
public class IntentController {

    private static final Set<String> FIND_CUES = Set.of(
            "найди", "найти", "поищи", "найдётся", "где мой", "где моя", "где мои", "где найти",
            "покажи мой", "покажи мою", "покажи документ", "в архиве", "мои документы",
            "find my", "find the", "search my", "where is my", "where's my", "look up my", "do i have");

    /**
     * Widen the search to the member's personal ∪ shared households (ADR-0002 slice 7b). Default is the
     * member's own archive; a family cue asks for "наши/семейные документы". Deterministic keyword match,
     * mirroring nutrition's {@code FAMILY_CUES} (a read-breadth choice, never a privacy write boundary).
     */
    private static final Set<String> FAMILY_CUES = Set.of(
            "наши документы", "наш документ", "семейные документы", "семейный документ", "у нас есть",
            "у нас дома", "домашние документы", "документы семьи", "на всю семью", "для семьи",
            "our documents", "our document", "family documents", "family document", "household documents",
            "do we have", "shared documents");

    private final DocArchiver archiver;
    private final DocFinder finder;
    private final DocsChat chat;

    public IntentController(DocArchiver archiver, DocFinder finder, DocsChat chat) {
        this.archiver = archiver;
        this.finder = finder;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> image = imageAttachment(message);
        if (image.isPresent()) {
            return archiver.archive(message, image.get().storageUri());
        }
        if (isMatch(message.text(), FIND_CUES)) {
            return finder.find(message, isMatch(message.text(), FAMILY_CUES));
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

    private static boolean isMatch(String text, Set<String> cues) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return cues.stream().anyMatch(t::contains);
    }
}
