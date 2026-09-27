package dev.fedorov.ailife.agents.inventory.find;

import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Где лежит X" (IN-e): answers <b>where</b> a stored thing is, not just whether it exists.
 *
 * <p>The phrase is distilled by the shared {@link ItemQuery} (the {@code item-finder} SKILL) because the
 * stored names came from photo captions — "где лежат ёлочные игрушки" must search
 * {@code ёлочные игрушки}, not the interrogatives around it. The search then returns each hit already
 * carrying its container and zone, so the reply is a place, in one round-trip.
 *
 * <p><b>Two sources, one answer (IN-e2).</b> The literal search runs alongside a semantic recall over the
 * second brain ({@link ItemNotes}), because the stored name is the vision model's vocabulary and the
 * question is the owner's ("та штука для гриля" vs "гриль-решётка чугунная"). Trigram hits come first —
 * they are relevance-ranked and exact — and the recall contributes whatever it alone found, de-duplicated
 * by item id. Each source soft-fails on its own: memory down still answers literally, and the store being
 * down is the only real failure. Scope is the envelope household — the personal ∪ shared widening comes
 * with the sharing retrofit.
 */
@Component
public class ItemFinder {

    private static final int LIMIT = 10;
    /** Beyond a handful the reply stops being an answer and becomes a list to read. */
    private static final int SHOWN = 5;

    private static final Logger log = LoggerFactory.getLogger(ItemFinder.class);

    private final ItemQuery query;
    private final ItemNotes notes;
    private final InventoryClient inventory;
    private final AgentManifest manifest;

    public ItemFinder(ItemQuery query, ItemNotes notes, InventoryClient inventory,
                      AgentManifest manifest) {
        this.query = query;
        this.notes = notes;
        this.inventory = inventory;
        this.manifest = manifest;
    }

    public Mono<IntentResponse> find(NormalizedMessage msg) {
        return query.distil(msg)
                .flatMap(phrase -> Mono.zip(trigram(msg, phrase), notes.recall(msg.householdId(), phrase))
                        .map(both -> new IntentResponse(manifest.name(),
                                answer(phrase, merge(both.getT1(), both.getT2())), null)
                                .withTrace("read: searched stored items")))
                .onErrorResume(e -> {
                    log.warn("item search failed: {}", e.toString());
                    return Mono.just(new IntentResponse(manifest.name(),
                            "Не получилось поискать прямо сейчас. Попробуйте ещё раз.", null));
                });
    }

    /**
     * The literal half. It soft-fails to an empty list rather than to the friendly apology, so a store
     * hiccup still lets a recalled thing be answered; a total outage then simply reads as "не нашёл".
     */
    private Mono<List<ItemLocationDto>> trigram(NormalizedMessage msg, String phrase) {
        return inventory.searchItems(msg.householdId(), phrase, LIMIT)
                .onErrorResume(e -> {
                    log.warn("trigram item search failed: {}", e.toString());
                    return Mono.just(List.of());
                });
    }

    /** Literal hits first (ranked), then the semantic-only extras, de-duplicated by item id and capped. */
    private static List<ItemLocationDto> merge(List<ItemLocationDto> trigram,
                                               List<ItemLocationDto> semantic) {
        Map<UUID, ItemLocationDto> byId = new LinkedHashMap<>();
        for (List<ItemLocationDto> source : List.of(trigram, semantic)) {
            for (ItemLocationDto hit : source) {
                if (hit != null && hit.item() != null && hit.item().id() != null) {
                    byId.putIfAbsent(hit.item().id(), hit);
                }
            }
        }
        List<ItemLocationDto> merged = new ArrayList<>(byId.values());
        return merged.size() > LIMIT ? merged.subList(0, LIMIT) : merged;
    }

    private static String answer(String query, List<ItemLocationDto> hits) {
        if (hits == null || hits.isEmpty()) {
            return "Не нашёл «" + query + "» ни в одной коробке. "
                    + "Возможно, вещь ещё не упакована или названа иначе.";
        }
        StringBuilder sb = new StringBuilder();
        ItemLocationDto first = hits.get(0);
        sb.append(name(first)).append(" — ").append(place(first)).append('.');

        List<ItemLocationDto> rest = hits.subList(1, Math.min(hits.size(), SHOWN));
        if (!rest.isEmpty()) {
            sb.append("\nТакже похоже:");
            rest.forEach(h -> sb.append("\n• ").append(name(h)).append(" — ").append(place(h)));
        }
        if (hits.size() > SHOWN) {
            sb.append("\n… и ещё ").append(hits.size() - SHOWN);
        }
        return sb.toString();
    }

    private static String name(ItemLocationDto hit) {
        String title = hit.item() == null ? null : hit.item().title();
        return (title == null || title.isBlank()) ? "Вещь без названия" : title;
    }

    /** A container may not be placed in a zone yet — say where it is anyway. */
    private static String place(ItemLocationDto hit) {
        ContainerDto container = hit.container();
        if (container == null) {
            return "место неизвестно";
        }
        StringBuilder sb = new StringBuilder("коробка ").append(container.code());
        if (container.label() != null && !container.label().isBlank()) {
            sb.append(" «").append(container.label()).append('»');
        }
        StorageZoneDto zone = hit.zone();
        if (zone != null && zone.name() != null && !zone.name().isBlank()) {
            sb.append(", ").append(zone.name());
        } else {
            sb.append(", зона не указана");
        }
        return sb.toString();
    }

}
