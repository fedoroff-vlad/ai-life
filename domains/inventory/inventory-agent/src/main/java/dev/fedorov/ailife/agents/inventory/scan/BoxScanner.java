package dev.fedorov.ailife.agents.inventory.scan;

import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.agents.inventory.label.BoxLabeler;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The scan path (IN-f): a printed label's token → that container's card.
 *
 * <p>A scan is <b>not a routed message</b>. The owner points a camera at a sticker and Telegram opens
 * {@code ?start=box_<token>}; there is no sentence to classify, so the gateway dispatches it
 * deterministically through the hub as an inter-agent action and this resolves it. That is why the
 * entry point takes ids + a token instead of a {@code NormalizedMessage}.
 *
 * <p><b>The token is the authorization.</b> It is opaque, unguessable and printed on a physical box
 * standing in the household's home, so a lookup is by token alone — no household match. That is
 * deliberate: the person unpacking may be a different member (or hold the box before their account
 * exists), and prior art converged on "scanning must work for a non-user".
 *
 * <p>An unknown token is a plain answer, never an invented box: a sticker outlives the row it points
 * at, and guessing a neighbouring container is exactly the error this domain exists to prevent.
 */
@Component
public class BoxScanner {

    private static final Logger log = LoggerFactory.getLogger(BoxScanner.class);

    private final InventoryClient inventory;
    private final BoxLabeler labeler;

    public BoxScanner(InventoryClient inventory, BoxLabeler labeler) {
        this.inventory = inventory;
        this.labeler = labeler;
    }

    /** What a scan hands back: the reply text, plus the card link it names (null when the render failed). */
    public record Scanned(String message, String cardUrl) {
    }

    /**
     * Resolve a scanned token and render its card. {@link Mono#empty()} means the token matches no
     * container — the caller answers that, it is not a failure. A render/store hiccup still answers with
     * the contents in text: the owner is standing in front of the box and needs to know what is in it.
     */
    public Mono<Scanned> scan(UUID householdId, UUID userId, String qrToken) {
        return inventory.getContainerByToken(qrToken)
                .flatMap(view -> labeler.cardUrl(householdId, userId, view)
                        .map(url -> new Scanned(BoxLabeler.cardText(view) + ": " + url, url))
                        .onErrorResume(e -> {
                            log.warn("scan card render failed for token {}: {}", qrToken, e.toString());
                            return Mono.just(new Scanned(withoutCard(view), null));
                        }));
    }

    private static String withoutCard(ContainerViewDto view) {
        int items = view.items() == null ? 0 : view.items().size();
        return BoxLabeler.cardText(view)
                + (items == 0 ? "." : ". Карточку сейчас не получилось собрать — попробуйте ещё раз.");
    }
}
