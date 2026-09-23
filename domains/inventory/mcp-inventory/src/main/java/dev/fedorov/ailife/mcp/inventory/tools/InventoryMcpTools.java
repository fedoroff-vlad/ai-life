package dev.fedorov.ailife.mcp.inventory.tools;

import dev.fedorov.ailife.contracts.inventory.ContainerDto;
import dev.fedorov.ailife.contracts.inventory.ContainerViewDto;
import dev.fedorov.ailife.contracts.inventory.ItemDto;
import dev.fedorov.ailife.contracts.inventory.ItemLocationDto;
import dev.fedorov.ailife.contracts.inventory.SaveContainerInput;
import dev.fedorov.ailife.contracts.inventory.SaveItemInput;
import dev.fedorov.ailife.contracts.inventory.SaveZoneInput;
import dev.fedorov.ailife.contracts.inventory.StorageZoneDto;
import dev.fedorov.ailife.mcp.inventory.domain.ContainerEntity;
import dev.fedorov.ailife.mcp.inventory.domain.ContainerRepository;
import dev.fedorov.ailife.mcp.inventory.domain.ItemEntity;
import dev.fedorov.ailife.mcp.inventory.domain.ItemRepository;
import dev.fedorov.ailife.mcp.inventory.domain.StorageZoneEntity;
import dev.fedorov.ailife.mcp.inventory.domain.StorageZoneRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Inventory domain opener (IN-a): source-of-truth store + search over inventory.* (physical storage:
 * zones → QR-labelled containers → photographed items). Intentionally low-level — it persists and
 * searches; the photo capture, the vision caption that names an item, and the QR rendering/decoding
 * live in inventory-agent (IN-c…IN-f), not here.
 *
 * Scope rule: zone/container tools take a householdId and read/write only within it (mirrors
 * mcp-docs / mcp-creator). Items inherit their household from the container they sit in. Per-person
 * attribution is the optional ownerId (null = household-shared).
 */
@Component
public class InventoryMcpTools {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final StorageZoneRepository zones;
    private final ContainerRepository containers;
    private final ItemRepository items;

    public InventoryMcpTools(StorageZoneRepository zones, ContainerRepository containers, ItemRepository items) {
        this.zones = zones;
        this.containers = containers;
        this.items = items;
    }

    // ── zones ────────────────────────────────────────────────────────────────────────────────────

    @Tool(description = """
            Create or rename a storage zone — a physical place that holds containers (кладовка,
            гараж, дача, балкон). `householdId` and `name` are required; a null `ownerId` is
            household-shared. `kind` is a coarse class (room|garage|dacha|balcony|closet|other).
            `labelColour` is the colour of the label stock this zone's containers print on (the
            thermal printer itself is black-only, so colour is chosen by loading a different roll).
            Upserted on (householdId, name) case-insensitively, so resolving the same spoken name
            twice returns the same zone rather than creating a duplicate. Returns the zone.
            """)
    @Transactional
    public StorageZoneDto saveZone(SaveZoneInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.name(), "name");
        String name = input.name().trim();
        StorageZoneEntity zone = zones.findByHouseholdAndName(input.householdId(), name)
                .orElseGet(() -> new StorageZoneEntity(
                        UUID.randomUUID(), input.householdId(), input.ownerId(), name));
        zone.setName(name);
        if (input.ownerId() != null) zone.setOwnerId(input.ownerId());
        if (input.kind() != null) zone.setKind(blankToNull(input.kind()));
        if (input.labelColour() != null) zone.setLabelColour(blankToNull(input.labelColour()));
        if (input.note() != null) zone.setNote(blankToNull(input.note()));
        return zones.save(zone).toDto();
    }

    @Tool(description = """
            List a household's storage zones, by name. Returns an empty list when it has none.
            """)
    @Transactional(readOnly = true)
    public List<StorageZoneDto> listZones(UUID householdId) {
        requireField(householdId, "householdId");
        return zones.findByHouseholdIdOrderByNameAsc(householdId).stream()
                .map(StorageZoneEntity::toDto).toList();
    }

    // ── containers ───────────────────────────────────────────────────────────────────────────────

    @Tool(description = """
            Create or update a container — whatever carries a label: a box, a shelf, a bin, or a
            zone's `loose` pseudo-container for open storage. A null `id` creates one: `householdId`
            is required, `qrToken` is minted (the printed identity) and `code` defaults to the next
            per-household "B-NN". A non-null `id` updates that container in place — its `qrToken` is
            never re-minted, so renaming it, moving it to another zone or changing its status leaves
            an already-printed label valid. `kind` defaults to box, `status` to open
            (open|packed|in_transit|unpacked); `destination` is the target room after a move.
            Returns the container.
            """)
    @Transactional
    public ContainerDto saveContainer(SaveContainerInput input) {
        ContainerEntity container;
        if (input.id() != null) {
            container = containers.findById(input.id()).orElseThrow(
                    () -> new IllegalArgumentException("No such container: " + input.id()));
        } else {
            requireField(input.householdId(), "householdId");
            String code = blankToNull(input.code()) != null
                    ? input.code().trim()
                    : nextCode(input.householdId());
            container = new ContainerEntity(UUID.randomUUID(), input.householdId(),
                    input.ownerId(), code, mintQrToken());
        }
        if (input.ownerId() != null) container.setOwnerId(input.ownerId());
        if (input.zoneId() != null) container.setZoneId(input.zoneId());
        if (input.id() != null && blankToNull(input.code()) != null) container.setCode(input.code().trim());
        if (input.label() != null) container.setLabel(blankToNull(input.label()));
        if (input.kind() != null) container.setKind(blankToNull(input.kind()));
        if (input.status() != null) container.setStatus(blankToNull(input.status()));
        if (input.destination() != null) container.setDestination(blankToNull(input.destination()));
        if (input.note() != null) container.setNote(blankToNull(input.note()));
        return containers.save(container).toDto();
    }

    @Tool(description = """
            Get one container by its id together with its zone and everything inside it (the shape a
            container card renders). Returns null if there is no such container.
            """)
    @Transactional(readOnly = true)
    public ContainerViewDto getContainer(UUID id) {
        requireField(id, "id");
        return containers.findById(id).map(this::toView).orElse(null);
    }

    @Tool(description = """
            Get a container by the `qrToken` printed on its label, together with its zone and
            everything inside it. This is what a scanned label resolves to. Returns null when the
            token is unknown.
            """)
    @Transactional(readOnly = true)
    public ContainerViewDto getContainerByToken(String qrToken) {
        requireField(qrToken, "qrToken");
        return containers.findByQrToken(qrToken.trim()).map(this::toView).orElse(null);
    }

    @Tool(description = """
            List a household's containers, newest first, optionally narrowed to one `zoneId` and/or
            `status` (open|packed|in_transit|unpacked). `limit` caps the result (default 50, max
            200). Returns an empty list when nothing matches.
            """)
    @Transactional(readOnly = true)
    public List<ContainerDto> listContainers(UUID householdId, UUID zoneId, String status, Integer limit) {
        requireField(householdId, "householdId");
        return containers.listIn(householdId, zoneId, blankToNull(status), clampLimit(limit))
                .stream().map(ContainerEntity::toDto).toList();
    }

    // ── items ────────────────────────────────────────────────────────────────────────────────────

    @Tool(description = """
            Put one photographed thing into a container. `containerId` and `mediaId` (the
            media-service object id of the photo) are required — the photo IS the record here. Each
            call stores a NEW item (append-only). `title`/`description` are what the vision caption
            produced and form the search corpus; `tags` is a free-form JSON array of labels; `qty`
            defaults to 1. Returns the stored item.
            """)
    @Transactional
    public ItemDto saveItem(SaveItemInput input) {
        requireField(input.containerId(), "containerId");
        requireField(input.mediaId(), "mediaId");
        if (!containers.existsById(input.containerId())) {
            throw new IllegalArgumentException("No such container: " + input.containerId());
        }
        ItemEntity item = new ItemEntity(UUID.randomUUID(), input.containerId(), input.mediaId());
        item.setTitle(blankToNull(input.title()));
        item.setDescription(blankToNull(input.description()));
        item.setTags(input.tags());
        item.setQty(input.qty());
        return items.save(item).toDto();
    }

    @Tool(description = """
            List everything inside a container, in the order it went in. Returns an empty list for an
            empty or unknown container.
            """)
    @Transactional(readOnly = true)
    public List<ItemDto> listItems(UUID containerId) {
        requireField(containerId, "containerId");
        return items.findByContainerIdOrderByCreatedAtAsc(containerId).stream()
                .map(ItemEntity::toDto).toList();
    }

    @Tool(description = """
            Remove one item by its id. Returns true when it existed and was removed, false when there
            was no such item.
            """)
    @Transactional
    public boolean deleteItem(UUID id) {
        requireField(id, "id");
        if (!items.existsById(id)) return false;
        items.deleteById(id);
        return true;
    }

    @Tool(description = """
            Search a household's stored things by free text over their title and description
            (case-insensitive) and return WHERE each match is: the item plus its container and zone.
            `limit` caps the result (default 50, max 200). Ranked by relevance, then recency. Returns
            an empty list when nothing matches. This is the cheap text match; semantic "где лежит X"
            recall is added on top in the agent.
            """)
    @Transactional(readOnly = true)
    public List<ItemLocationDto> searchItems(UUID householdId, String query, Integer limit) {
        requireField(householdId, "householdId");
        requireField(query, "query");
        List<ItemEntity> hits = items.search(householdId, query.trim(), clampLimit(limit));
        if (hits.isEmpty()) return List.of();

        // Resolve each hit's place in two batched reads rather than per-row lookups.
        Map<UUID, ContainerEntity> containerById = new HashMap<>();
        containers.findAllById(hits.stream().map(ItemEntity::getContainerId).distinct().toList())
                .forEach(c -> containerById.put(c.getId(), c));
        Map<UUID, StorageZoneEntity> zoneById = new HashMap<>();
        zones.findAllById(containerById.values().stream()
                        .map(ContainerEntity::getZoneId).filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(z -> zoneById.put(z.getId(), z));

        return hits.stream().map(i -> {
            ContainerEntity c = containerById.get(i.getContainerId());
            StorageZoneEntity z = (c == null || c.getZoneId() == null) ? null : zoneById.get(c.getZoneId());
            return new ItemLocationDto(i.toDto(),
                    c == null ? null : c.toDto(),
                    z == null ? null : z.toDto());
        }).toList();
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private ContainerViewDto toView(ContainerEntity container) {
        StorageZoneDto zone = Optional.ofNullable(container.getZoneId())
                .flatMap(zones::findById).map(StorageZoneEntity::toDto).orElse(null);
        List<ItemDto> contents = items.findByContainerIdOrderByCreatedAtAsc(container.getId())
                .stream().map(ItemEntity::toDto).toList();
        return new ContainerViewDto(container.toDto(), zone, contents);
    }

    /**
     * Short, human, printed beside the QR so a box stays identifiable when the code won't scan.
     * Derived from the household's container count, which two simultaneous creates could collide on
     * — the (household_id, code) unique index rejects the loser, and the caller retries.
     */
    private String nextCode(UUID householdId) {
        return "B-%02d".formatted(containers.countByHouseholdId(householdId) + 1);
    }

    /**
     * 16 hex chars (64 random bits) — enough for a personal household, and short enough that the
     * deep-link payload keeps the printed QR sparse, which matters on a small label.
     */
    private static String mintQrToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
