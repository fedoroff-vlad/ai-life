package dev.fedorov.ailife.mcp.nutrition.tools;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.nutrition.BasketDto;
import dev.fedorov.ailife.contracts.nutrition.BasketItem;
import dev.fedorov.ailife.contracts.nutrition.DietProfileDto;
import dev.fedorov.ailife.contracts.nutrition.LogMealInput;
import dev.fedorov.ailife.contracts.nutrition.MealLogDto;
import dev.fedorov.ailife.contracts.nutrition.SaveBasketInput;
import dev.fedorov.ailife.contracts.nutrition.SetDietProfileInput;
import dev.fedorov.ailife.mcp.nutrition.domain.Basket;
import dev.fedorov.ailife.mcp.nutrition.domain.BasketRepository;
import dev.fedorov.ailife.mcp.nutrition.domain.DietProfile;
import dev.fedorov.ailife.mcp.nutrition.domain.DietProfileRepository;
import dev.fedorov.ailife.mcp.nutrition.domain.MealLog;
import dev.fedorov.ailife.mcp.nutrition.domain.MealLogRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Nutrition domain opener (NU-a): source-of-truth CRUD over nutrition.* (logged meals + per-person
 * diet profiles + analysed grocery baskets). The food-log / analyse / basket-breakdown flows live in
 * nutritionist-agent; this MCP is intentionally low-level — it just persists what the agent extracts.
 *
 * Scope rule: every tool takes a householdId and reads/writes only within that household
 * (mirrors mcp-wardrobe / mcp-tasks). Per-person attribution is the optional ownerId.
 */
@Component
public class NutritionMcpTools {

    /** Default and hard caps for the list tools, so an unbounded read can't sweep the table. */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final MealLogRepository meals;
    private final DietProfileRepository profiles;
    private final BasketRepository baskets;
    private final ObjectMapper mapper;

    public NutritionMcpTools(MealLogRepository meals, DietProfileRepository profiles,
                             BasketRepository baskets, ObjectMapper mapper) {
        this.meals = meals;
        this.profiles = profiles;
        this.baskets = baskets;
        this.mapper = mapper;
    }

    // ---------- meal log ----------

    @Tool(description = """
            Log a meal. Only `householdId` + `description` are required; `eatenAt` defaults to now and
            `source` to `text` when omitted. Set `ownerId` to attribute the meal to a person (null =
            household-shared). `items` is the free-form parsed food breakdown; `kcal`/`proteinG`/
            `fatG`/`carbsG` are best-effort macro estimates. `imageMediaId` links a stored meal photo.
            """)
    @Transactional
    public MealLogDto logMeal(LogMealInput input) {
        requireField(input.householdId(), "householdId");
        requireField(input.description(), "description");
        Instant eatenAt = input.eatenAt() != null ? input.eatenAt() : Instant.now();
        MealLog meal = new MealLog(UUID.randomUUID(), input.householdId(), input.ownerId(),
                eatenAt, input.description());
        meal.setSource(input.source() != null && !input.source().isBlank() ? input.source() : "text");
        meal.setItems(input.items());
        meal.setKcal(input.kcal());
        meal.setProteinG(input.proteinG());
        meal.setFatG(input.fatG());
        meal.setCarbsG(input.carbsG());
        meal.setImageMediaId(input.imageMediaId());
        return meals.save(meal).toDto();
    }

    @Tool(description = """
            List logged meals in a household, most recently eaten first. Pass `ownerId` to scope to
            one person; omit it for the whole household. `limit` caps the count (default 20, max 200).
            """)
    @Transactional(readOnly = true)
    public List<MealLogDto> listMeals(UUID householdId, UUID ownerId, Integer limit) {
        requireField(householdId, "householdId");
        Pageable page = PageRequest.of(0, clampLimit(limit));
        List<MealLog> rows = ownerId == null
                ? meals.findByHouseholdIdOrderByEatenAtDesc(householdId, page)
                : meals.findByHouseholdIdAndOwnerIdOrderByEatenAtDesc(householdId, ownerId, page);
        return rows.stream().map(MealLog::toDto).toList();
    }

    @Tool(description = """
            Delete a logged meal and return the deleted row (so the agent can confirm / offer undo).
            Throws if the id is unknown. Confirming the destructive action is the agent layer's job.
            """)
    @Transactional
    public MealLogDto deleteMeal(UUID id) {
        requireField(id, "id");
        MealLog meal = meals.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Meal not found: " + id));
        MealLogDto dto = meal.toDto();
        meals.delete(meal);
        return dto;
    }

    // ---------- diet profile ----------

    @Tool(description = """
            Upsert a person's diet profile. Keyed on (householdId, ownerId); a null ownerId is the
            household-default profile. `householdId` is required. This is a full set: every supplied
            field overwrites the stored value. `restrictions` (allergies / halal / vegan / infant-stage
            / …) and `tastes` are free-form JSON; the macro goals are optional.
            """)
    @Transactional
    public DietProfileDto setDietProfile(SetDietProfileInput input) {
        requireField(input.householdId(), "householdId");
        DietProfile profile = profiles.findForOwner(input.householdId(), input.ownerId())
                .orElseGet(() -> new DietProfile(
                        UUID.randomUUID(), input.householdId(), input.ownerId()));
        profile.setGoalKcal(input.goalKcal());
        profile.setGoalProteinG(input.goalProteinG());
        profile.setGoalFatG(input.goalFatG());
        profile.setGoalCarbsG(input.goalCarbsG());
        profile.setRestrictions(input.restrictions());
        profile.setTastes(input.tastes());
        profile.setNotes(input.notes());
        return profiles.save(profile).toDto();
    }

    @Tool(description = """
            Get the diet profile for a person, treating a null `ownerId` as the household-default
            profile. Returns null if no profile has been set yet.
            """)
    @Transactional(readOnly = true)
    public DietProfileDto getDietProfile(UUID householdId, UUID ownerId) {
        requireField(householdId, "householdId");
        return profiles.findForOwner(householdId, ownerId)
                .map(DietProfile::toDto)
                .orElse(null);
    }

    // ---------- basket ----------

    @Tool(description = """
            Save an analysed grocery basket. Only `householdId` is required; `capturedAt` defaults to
            now and `source` to `manual` when omitted. `items` are the parsed line items, the macro
            fields are basket totals, and `analysis` is the optional breakdown. `receiptMediaId` links
            the source receipt photo.
            """)
    @Transactional
    public BasketDto saveBasket(SaveBasketInput input) {
        requireField(input.householdId(), "householdId");
        Instant capturedAt = input.capturedAt() != null ? input.capturedAt() : Instant.now();
        Basket basket = new Basket(UUID.randomUUID(), input.householdId(), input.ownerId(), capturedAt);
        basket.setMerchant(input.merchant());
        basket.setSource(input.source() != null && !input.source().isBlank() ? input.source() : "manual");
        basket.setReceiptMediaId(input.receiptMediaId());
        basket.setItems(itemsToJson(input.items()));
        basket.setKcal(input.kcal());
        basket.setProteinG(input.proteinG());
        basket.setFatG(input.fatG());
        basket.setCarbsG(input.carbsG());
        basket.setAnalysis(input.analysis());
        return toDto(baskets.save(basket));
    }

    @Tool(description = """
            List analysed grocery baskets in a household, most recently captured first. `limit` caps
            the count (default 20, max 200).
            """)
    @Transactional(readOnly = true)
    public List<BasketDto> listBaskets(UUID householdId, Integer limit) {
        requireField(householdId, "householdId");
        return baskets.findByHouseholdIdOrderByCapturedAtDesc(householdId, PageRequest.of(0, clampLimit(limit)))
                .stream().map(this::toDto).toList();
    }

    @Tool(description = """
            Get one analysed grocery basket by id, or null if it doesn't exist.
            """)
    @Transactional(readOnly = true)
    public BasketDto getBasket(UUID id) {
        requireField(id, "id");
        return baskets.findById(id).map(this::toDto).orElse(null);
    }

    // ---------- helpers ----------

    private JsonNode itemsToJson(List<BasketItem> items) {
        return items == null ? null : mapper.valueToTree(items);
    }

    private BasketDto toDto(Basket b) {
        List<BasketItem> items = b.getItems() == null ? null
                : mapper.convertValue(b.getItems(), new TypeReference<List<BasketItem>>() {});
        return new BasketDto(b.getId(), b.getHouseholdId(), b.getOwnerId(), b.getCapturedAt(),
                b.getMerchant(), b.getSource(), b.getReceiptMediaId(), items, b.getKcal(),
                b.getProteinG(), b.getFatG(), b.getCarbsG(), b.getAnalysis(), b.getCreatedAt());
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
