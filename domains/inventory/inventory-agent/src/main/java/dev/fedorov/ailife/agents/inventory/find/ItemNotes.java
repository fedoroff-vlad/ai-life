package dev.fedorov.ailife.agents.inventory.find;

import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.agents.inventory.http.InventoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

/**
 * The item ↔ second-brain seam (IN-e2): a packed thing's note, written on the way in and read on the
 * way out.
 *
 * <p>Why it exists: the thing's name came from a <b>vision caption</b> ("гриль-решётка чугунная"), and
 * months later the owner asks for it in their own words ("та штука для гриля"). A trigram match over
 * titles cannot bridge that, so each saved item also seeds an authored note (SB-5 shape, as
 * {@code doc-archiver} does for a document) — memory-service embeds it (SB-2), and the finder recalls by
 * meaning alongside the literal search.
 *
 * <p><b>Both directions live here on purpose.</b> The {@code {kind:item, refId, containerId}} frontmatter
 * is a shape written in one flow and read in another; keeping the writer and the reader in one file is
 * what stops it drifting apart. It also means the back-pointer can carry {@code containerId}, which is
 * why resolving a recall hit needs <b>no new store endpoint</b>: the existing {@code getContainer} read
 * returns the container, its zone and its items, so the item is picked out by {@code refId} and an
 * {@link ItemLocationDto} composed — the same shape the trigram search returns.
 *
 * <p>Everything here soft-fails. The store is the record and the note is only an index: a memory-service
 * outage must cost neither the item on the way in nor the literal hits on the way out.
 */
@Component
public class ItemNotes {

    private static final Logger log = LoggerFactory.getLogger(ItemNotes.class);

    /** A stored thing is reference material, like an archived document (the note-tier {@code type}). */
    private static final String NOTE_TYPE = "reference";
    private static final String NOTE_SOURCE = "inventory-agent";
    private static final String NOTE_TAG = "item";
    /** What a recall hit looks like for a note's seed (SB-2): {@code source=note}, {@code kind=note}. */
    private static final String NOTE_MEMORY_SOURCE = "note";
    private static final String NOTE_KIND = "note";
    /** The {@code frontmatter.kind} an inventory note carries, so only item notes are resolved. */
    private static final String ITEM_KIND = "item";

    private final MemoryClient memory;
    private final InventoryClient inventory;
    private final ObjectMapper json;

    public ItemNotes(MemoryClient memory, InventoryClient inventory, ObjectMapper json) {
        this.memory = memory;
        this.inventory = inventory;
        this.json = json;
    }

    /**
     * Seed the note for a thing that just went into a box. Never fails and never delays the caller's
     * answer beyond the write itself — the item is already stored, so a missing note costs the semantic
     * half of one future search, nothing else.
     *
     * <p>An <b>unnamed</b> item is skipped: a note needs a non-blank title, and "вещь без названия"
     * embeds to a vector that matches every query weakly, which is worse than being absent.
     */
    public Mono<Void> seed(UUID householdId, UUID ownerId, ItemDto item, UUID containerId) {
        String title = item == null ? null : blankToNull(item.title());
        if (householdId == null || item == null || item.id() == null || title == null) {
            return Mono.empty();
        }
        return memory.note(new WriteNoteRequest(
                        householdId,
                        ownerId,                 // seeded under whoever packed it; recall stays household-scoped
                        title,
                        NOTE_TYPE,
                        List.of(NOTE_TAG),
                        NOTE_SOURCE,
                        null,                    // personId — a boxed thing is not a person note
                        body(item),              // the corpus recall embeds
                        frontmatter(item, containerId)))
                .doOnError(e -> log.debug("item note seed failed for item {}: {}", item.id(), e.toString()))
                .onErrorComplete()
                .then();
    }

    /**
     * Recall the things a phrase means rather than matches: embed it, keep the note hits, resolve each
     * note's {@code {kind:item, refId, containerId}} back-pointer to its container view and pick the item
     * out of it. A note whose item is gone (taken out of the box since) is skipped, not reported — the
     * owner asked where a thing is, and it is nowhere. Soft-fails to an empty list; this is a bonus on
     * top of the trigram search, never a dependency.
     */
    public Mono<List<ItemLocationDto>> recall(UUID householdId, String phrase) {
        if (householdId == null || phrase == null || phrase.isBlank()) {
            return Mono.just(List.of());
        }
        return memory.recall(householdId, null, null, phrase)
                .flatMapMany(Flux::fromIterable)
                .map(this::noteId)
                .filter(id -> id != null)
                .distinct()
                .flatMap(noteId -> memory.getNote(noteId).flatMap(this::resolve))
                .collectList()
                .onErrorResume(e -> {
                    log.warn("item recall failed: {}", e.toString());
                    return Mono.just(List.of());
                });
    }

    /** A note's back-pointer → the item in its place, or empty when the note is not ours / the item is gone. */
    private Mono<ItemLocationDto> resolve(NoteDto note) {
        JsonNode fm = note == null ? null : note.frontmatter();
        if (fm == null || !ITEM_KIND.equals(text(fm, "kind"))) {
            return Mono.empty();                 // not an inventory note — skip
        }
        UUID itemId = parseUuid(text(fm, "refId"));
        UUID containerId = parseUuid(text(fm, "containerId"));
        if (itemId == null || containerId == null) {
            return Mono.empty();
        }
        return inventory.getContainer(containerId)
                .flatMap(view -> Mono.justOrEmpty(locate(view, itemId)))
                .onErrorResume(e -> {
                    log.debug("resolving recalled item {} failed: {}", itemId, e.toString());
                    return Mono.empty();
                });
    }

    /** The item inside a container view, as the search's own hit shape. Empty when it is no longer there. */
    private static ItemLocationDto locate(ContainerViewDto view, UUID itemId) {
        if (view == null || view.items() == null) {
            return null;
        }
        ContainerDto container = view.container();
        return view.items().stream()
                .filter(i -> i != null && itemId.equals(i.id()))
                .findFirst()
                .map(i -> new ItemLocationDto(i, container, view.zone()))
                .orElse(null);
    }

    /** A recall hit's note id: only {@code source=note}, {@code kind=note} memories carry the {@code refId}. */
    private UUID noteId(RecallMemoryHit hit) {
        if (hit == null || hit.memory() == null || !NOTE_MEMORY_SOURCE.equals(hit.memory().source())) {
            return null;
        }
        JsonNode meta = hit.memory().metadata();
        if (meta == null || !NOTE_KIND.equals(text(meta, "kind"))) {
            return null;
        }
        return parseUuid(text(meta, "refId"));
    }

    /** The corpus recall embeds: the thing's name plus whatever else describes it. */
    private static String body(ItemDto item) {
        String description = blankToNull(item.description());
        return description == null ? item.title().trim() : item.title().trim() + "\n\n" + description;
    }

    /** {@code {kind:item, refId, containerId}} — `containerId` is what spares the reader a new endpoint. */
    private ObjectNode frontmatter(ItemDto item, UUID containerId) {
        ObjectNode fm = json.createObjectNode();
        fm.put("kind", ITEM_KIND);
        fm.put("refId", item.id().toString());
        UUID container = containerId != null ? containerId : item.containerId();
        if (container != null) {
            fm.put("containerId", container.toString());
        }
        return fm;
    }

    private static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String v = node.get(field).asString();
        return v.isBlank() ? null : v;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
