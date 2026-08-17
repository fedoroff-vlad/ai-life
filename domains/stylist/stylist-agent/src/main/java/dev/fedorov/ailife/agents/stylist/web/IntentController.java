package dev.fedorov.ailife.agents.stylist.web;

import dev.fedorov.ailife.agents.stylist.analyse.AnalyseMe;
import dev.fedorov.ailife.agents.stylist.catalogue.WardrobeCataloguer;
import dev.fedorov.ailife.agents.stylist.intent.StylistIntentRouter;
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
 * Hit by the orchestrator when intent routing selects {@code stylist}:
 * <ul>
 *   <li>a photo attachment with an "analyse me" cue (or stated body params) → {@link AnalyseMe} (ST-d) —
 *       build the style profile;</li>
 *   <li>any other photo attachment → {@link WardrobeCataloguer} (ST-c) — catalogue the garment (the
 *       default, since the owner bulk-uploads the wardrobe);</li>
 *   <li>otherwise → {@link StylistIntentRouter}, which classifies the text (via the shared
 *       {@code SkillClassifier}) into one of the three text-intent flows — wardrobe audit / gap analysis /
 *       outfit capsule — or a plain chat reply.</li>
 * </ul>
 * The photo check comes first (a photo is unambiguously a catalogue/analyse ingest, not a text intent, so
 * it stays a deterministic pre-check rather than an LLM classification; the analyse-vs-catalogue split is
 * likewise a deterministic keyword on the caption). The typed-message keyword-cue heuristic this controller
 * used to carry ({@code AUDIT_CUES}/{@code GAP_CUES}/{@code CAPSULE_CUES}) was replaced by the LLM
 * classifier in skills-vs-flows Bucket 1 (#475), so the routing SSOT is each intent skill's SKILL.md
 * description.
 */
@RestController
@RequestMapping("/agents/stylist")
public class IntentController {

    private static final Set<String> ANALYSE_CUES = Set.of(
            "проанализир", "анализ", "цветотип", "это я", "моя фигура", "мои парам",
            "рост", "вес", "разбери меня", "какой мне", "что мне идёт", "что мне идет",
            "analyse me", "analyze me", "my style", "colour type", "color type",
            "body shape", "height", "weight");

    private final WardrobeCataloguer cataloguer;
    private final AnalyseMe analyseMe;
    private final StylistIntentRouter router;

    public IntentController(WardrobeCataloguer cataloguer, AnalyseMe analyseMe, StylistIntentRouter router) {
        this.cataloguer = cataloguer;
        this.analyseMe = analyseMe;
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> image = attachment(message, "image");
        if (image.isPresent()) {
            String mediaId = image.get().storageUri();
            return isMatch(message.text(), ANALYSE_CUES)
                    ? analyseMe.analyse(message, mediaId)
                    : cataloguer.catalogue(message, mediaId);
        }
        return router.route(message);
    }

    private static boolean isMatch(String text, Set<String> cues) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return cues.stream().anyMatch(t::contains);
    }

    private static Optional<Attachment> attachment(NormalizedMessage message, String kind) {
        return message.attachments().stream()
                .filter(a -> kind.equals(a.kind()) && a.storageUri() != null)
                .findFirst();
    }
}
