package dev.fedorov.ailife.mcp.wardrobe.tools;

import dev.fedorov.ailife.contracts.wardrobe.AddItemInput;
import dev.fedorov.ailife.contracts.wardrobe.SetStyleProfileInput;
import dev.fedorov.ailife.contracts.wardrobe.StyleProfileDto;
import dev.fedorov.ailife.contracts.wardrobe.UpdateItemInput;
import dev.fedorov.ailife.contracts.wardrobe.WardrobeItemDto;
import dev.fedorov.ailife.mcp.wardrobe.domain.StyleProfile;
import dev.fedorov.ailife.mcp.wardrobe.domain.StyleProfileRepository;
import dev.fedorov.ailife.mcp.wardrobe.domain.WardrobeItem;
import dev.fedorov.ailife.mcp.wardrobe.domain.WardrobeItemRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Stylist domain opener (ST-a): source-of-truth wardrobe CRUD over wardrobe.* (garments +
 * the per-person style profile). The catalogue / "analyse me" / capsule flows live in
 * stylist-agent; this MCP is intentionally low-level — it just persists what the agent
 * extracts.
 *
 * Scope rule: every tool takes a householdId and reads/writes only within that household.
 * Per-user privacy (owner_id filtering for "private" items) is the agent layer's job
 * (mirrors mcp-tasks / mcp-finance).
 */
@Component
public class WardrobeMcpTools {

    private final WardrobeItemRepository items;
    private final StyleProfileRepository profiles;

    public WardrobeMcpTools(WardrobeItemRepository items, StyleProfileRepository profiles) {
        this.items = items;
        this.profiles = profiles;
    }

    @Tool(description = """
            Add a garment to the wardrobe catalogue. Only `householdId` + `name` are required;
            the descriptive fields (category top|bottom|outerwear|shoes|accessory|…, colour,
            material, pattern, season, formality) are stored when supplied (the catalogue flow
            fills them from a vision extract). `imageMediaId` links the stored photo. Set
            `ownerId` for a private garment; leave null for household-shared.
            """)
    @Transactional
    public WardrobeItemDto addItem(AddItemInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.name(), "name");
        WardrobeItem entity = new WardrobeItem(
                UUID.randomUUID(), input.householdId(), input.ownerId(), input.name());
        entity.setCategory(input.category());
        entity.setColour(input.colour());
        entity.setMaterial(input.material());
        entity.setPattern(input.pattern());
        entity.setSeason(input.season());
        entity.setFormality(input.formality());
        entity.setImageMediaId(input.imageMediaId());
        return items.save(entity).toDto();
    }

    @Tool(description = """
            List garments in a household, newest first. Pass `category` to filter
            (top|bottom|outerwear|shoes|accessory|…); omit it to list the whole wardrobe.
            """)
    @Transactional(readOnly = true)
    public List<WardrobeItemDto> listItems(UUID householdId, String category) {
        requireField(householdId, "householdId");
        List<WardrobeItem> rows = (category == null || category.isBlank())
                ? items.findByHouseholdIdOrderByCreatedAtDesc(householdId)
                : items.findByHouseholdIdAndCategoryOrderByCreatedAtDesc(householdId, category);
        return rows.stream().map(WardrobeItem::toDto).toList();
    }

    @Tool(description = """
            Partial content edit of a catalogued garment. `id` is required; every other field is
            applied only when non-null (null = leave unchanged), so it corrects a misclassified
            colour/category but cannot clear a set field. household/createdAt are immutable.
            """)
    @Transactional
    public WardrobeItemDto updateItem(UpdateItemInput input) {
        requireField(input.id(), "id");
        WardrobeItem item = items.findById(input.id()).orElseThrow(
                () -> new IllegalArgumentException("Wardrobe item not found: " + input.id()));
        if (input.name() != null && !input.name().isBlank()) item.setName(input.name());
        if (input.category() != null) item.setCategory(input.category());
        if (input.colour() != null) item.setColour(input.colour());
        if (input.material() != null) item.setMaterial(input.material());
        if (input.pattern() != null) item.setPattern(input.pattern());
        if (input.season() != null) item.setSeason(input.season());
        if (input.formality() != null) item.setFormality(input.formality());
        if (input.imageMediaId() != null) item.setImageMediaId(input.imageMediaId());
        return items.save(item).toDto();
    }

    @Tool(description = """
            Delete a garment and return the deleted row (so the agent can confirm / offer undo).
            Throws if the id is unknown. Confirming the destructive action with the user is the
            agent layer's job.
            """)
    @Transactional
    public WardrobeItemDto deleteItem(UUID id) {
        requireField(id, "id");
        WardrobeItem item = items.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Wardrobe item not found: " + id));
        WardrobeItemDto dto = item.toDto();
        items.delete(item);
        return dto;
    }

    @Tool(description = """
            Upsert the style profile for a person — the "analyse me" result. Keyed on
            (householdId, ownerId); a null ownerId is the household-default profile. `householdId`
            is required. This is a full set: every supplied field overwrites the stored value
            (the analyse-me flow recomputes the whole profile). `suitableFabrics` and
            `measurements` are free-form JSON.
            """)
    @Transactional
    public StyleProfileDto setStyleProfile(SetStyleProfileInput input) {
        requireField(input.householdId(), "householdId");
        StyleProfile profile = profiles.findForOwner(input.householdId(), input.ownerId())
                .orElseGet(() -> new StyleProfile(
                        UUID.randomUUID(), input.householdId(), input.ownerId()));
        profile.setPersonType(input.personType());
        profile.setBodyShape(input.bodyShape());
        profile.setColourType(input.colourType());
        profile.setSuitableFabrics(input.suitableFabrics());
        profile.setHeightCm(input.heightCm());
        profile.setWeightKg(input.weightKg());
        profile.setMeasurements(input.measurements());
        profile.setNotes(input.notes());
        profile.setImageMediaId(input.imageMediaId());
        return profiles.save(profile).toDto();
    }

    @Tool(description = """
            Get the style profile for a person, treating a null `ownerId` as the
            household-default profile. Returns null if no profile has been set yet.
            """)
    @Transactional(readOnly = true)
    public StyleProfileDto getStyleProfile(UUID householdId, UUID ownerId) {
        requireField(householdId, "householdId");
        return profiles.findForOwner(householdId, ownerId)
                .map(StyleProfile::toDto)
                .orElse(null);
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
