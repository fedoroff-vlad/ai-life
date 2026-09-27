package dev.fedorov.ailife.agents.inventory.container;

import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * "Which box did the owner mean" — the household's containers matched against the phrase they said.
 *
 * <p><b>Code first, label second</b>, and that order is the domain's whole point: the code is the
 * <i>printed</i> identity a person reads off the side of a box ("B-07"), while the label is what they
 * call it ("кухня — посуда"). Matching the label first would let a spoken nickname outrank a code the
 * owner is literally looking at.
 *
 * <p>Lifted here on its <b>second consumer</b> (the label/card deliverables and the append flow both
 * need it) per the repo's second-consumer rule; the LLM distil that produces the phrase stays with each
 * caller, because each one reads it out of its own SKILL's JSON.
 *
 * <p>Deliberately no fuzzy fallback: an unresolved box is answered ("назовите код"), never guessed. A
 * wrong guess here prints a sticker for the wrong box or files a thing where nobody will look for it.
 */
@Component
public class ContainerResolver {

    /** A household has tens of containers, not thousands — one page is the whole set to match over. */
    private static final int SCAN_LIMIT = 500;

    private final InventoryClient inventory;

    public ContainerResolver(InventoryClient inventory) {
        this.inventory = inventory;
    }

    /** The container the phrase names, or empty when nothing matches. */
    public Mono<Optional<ContainerDto>> byPhrase(UUID householdId, String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return Mono.just(Optional.empty());
        }
        return inventory.listContainers(householdId, SCAN_LIMIT).map(all -> match(all, phrase));
    }

    /** The same matching over an already-read list (for a caller that has the containers in hand). */
    public static Optional<ContainerDto> match(List<ContainerDto> all, String phrase) {
        if (all == null || phrase == null || phrase.isBlank()) {
            return Optional.empty();
        }
        String want = phrase.toLowerCase(Locale.ROOT).strip();
        return all.stream()
                .filter(c -> c.code() != null && c.code().equalsIgnoreCase(want))
                .findFirst()
                .or(() -> all.stream()
                        .filter(c -> c.label() != null
                                && c.label().toLowerCase(Locale.ROOT).contains(want))
                        .findFirst());
    }
}
