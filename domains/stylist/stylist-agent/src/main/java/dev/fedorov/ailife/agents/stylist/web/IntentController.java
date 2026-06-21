package dev.fedorov.ailife.agents.stylist.web;

import dev.fedorov.ailife.agents.stylist.analyse.AnalyseMe;
import dev.fedorov.ailife.agents.stylist.catalogue.WardrobeCataloguer;
import dev.fedorov.ailife.agents.stylist.chat.StylistChat;
import dev.fedorov.ailife.agents.stylist.flow.StylistAdvisor;
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
 * Hit by the orchestrator when intent routing selects {@code stylist}. A photo attachment routes to
 * one of two flows by the caption text:
 * <ul>
 *   <li>"analyse me" cues (or stated body params) → {@link AnalyseMe} (ST-d) — build the style profile;</li>
 *   <li>otherwise → {@link WardrobeCataloguer} (ST-c) — catalogue the garment (the default, since the
 *       owner bulk-uploads the wardrobe).</li>
 * </ul>
 * A non-photo message with a capsule cue → the {@link StylistAdvisor} capsule flow (ST-e);
 * otherwise it falls back to chat ({@link StylistChat}).
 *
 * <p>The route splits are deterministic keyword heuristics on the text — good enough for the MVP
 * (a self-photo almost always comes with "проанализируй меня" / body params; a capsule ask says
 * "что надеть" / "собери капсулу"), MockWebServer-testable, and replaceable by an LLM classifier later.
 */
@RestController
@RequestMapping("/agents/stylist")
public class IntentController {

    private static final Set<String> ANALYSE_CUES = Set.of(
            "проанализир", "анализ", "цветотип", "это я", "моя фигура", "мои парам",
            "рост", "вес", "разбери меня", "какой мне", "что мне идёт", "что мне идет",
            "analyse me", "analyze me", "my style", "colour type", "color type",
            "body shape", "height", "weight");

    private static final Set<String> CAPSULE_CUES = Set.of(
            "капсул", "что надеть", "что мне надеть", "собери", "собрать", "образ", "лук",
            "наряд", "во что одеться", "outfit", "what to wear", "capsule", "look");

    private final WardrobeCataloguer cataloguer;
    private final AnalyseMe analyseMe;
    private final StylistAdvisor advisor;
    private final StylistChat chat;

    public IntentController(WardrobeCataloguer cataloguer, AnalyseMe analyseMe,
                            StylistAdvisor advisor, StylistChat chat) {
        this.cataloguer = cataloguer;
        this.analyseMe = analyseMe;
        this.advisor = advisor;
        this.chat = chat;
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
        if (isMatch(message.text(), CAPSULE_CUES)) {
            return advisor.advise(message);
        }
        return chat.reply(message);
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
