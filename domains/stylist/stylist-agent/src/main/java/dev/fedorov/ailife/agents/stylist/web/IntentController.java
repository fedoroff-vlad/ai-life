package dev.fedorov.ailife.agents.stylist.web;

import dev.fedorov.ailife.agents.stylist.catalogue.WardrobeCataloguer;
import dev.fedorov.ailife.agents.stylist.chat.StylistChat;
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
 * Hit by the orchestrator when intent routing selects {@code stylist}. A photo attachment →
 * the wardrobe-catalogue flow (ST-c); otherwise the chat fallback (see {@link StylistChat}). The
 * analyse-me / capsule flows replace further branches as they land (ST-d..e).
 */
@RestController
@RequestMapping("/agents/stylist")
public class IntentController {

    private final WardrobeCataloguer cataloguer;
    private final StylistChat chat;

    public IntentController(WardrobeCataloguer cataloguer, StylistChat chat) {
        this.cataloguer = cataloguer;
        this.chat = chat;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> image = attachment(message, "image");
        if (image.isPresent()) {
            return cataloguer.catalogue(message, image.get().storageUri());
        }
        return chat.reply(message);
    }

    private static Optional<Attachment> attachment(NormalizedMessage message, String kind) {
        return message.attachments().stream()
                .filter(a -> kind.equals(a.kind()) && a.storageUri() != null)
                .findFirst();
    }
}
