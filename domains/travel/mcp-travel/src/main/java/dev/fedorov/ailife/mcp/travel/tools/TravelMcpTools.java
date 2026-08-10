package dev.fedorov.ailife.mcp.travel.tools;

import dev.fedorov.ailife.contracts.travel.SetTravelProfileInput;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.mcp.travel.domain.TravelProfile;
import dev.fedorov.ailife.mcp.travel.domain.TravelProfileRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Travel domain opener (TR-b): source-of-truth CRUD over travel.* (per-person travel preferences).
 * The gather → synthesize trip-planning flow lives in travel-agent; this MCP is intentionally
 * low-level — it just persists the preferences the travel-profiler extracts (vocabulary enforcement
 * for rest_types/companions happens in the profiler, not here).
 *
 * Scope rule: every tool takes a householdId and reads/writes only within that household (mirrors
 * mcp-briefing / mcp-creator). Per-person attribution is the optional ownerId (null = household-default).
 */
@Component
public class TravelMcpTools {

    private final TravelProfileRepository profiles;

    public TravelMcpTools(TravelProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Tool(description = """
            Upsert a person's travel preferences. Keyed on (householdId, ownerId); a null ownerId is
            the household-default. `householdId` is required. This is a full set: every supplied field
            overwrites the stored value. `homeBaseLatitude`/`homeBaseLongitude` are the geocoded
            coordinates of `homeBaseLabel` (the agent geocodes a stated city before calling).
            `restTypes` is a JSON array of preferred vacation kinds (beach|active|family|couple|city|
            ski|wellness); `companions` is solo|couple|family; `childAges` is an optional JSON array of
            ints; `budgetAmount`/`budgetCurrency` are a soft budget hint (the live check comes from the
            finance brief).
            """)
    @Transactional
    public TravelProfileDto setTravelProfile(SetTravelProfileInput input) {
        requireField(input.householdId(), "householdId");
        TravelProfile profile = profiles.findForOwner(input.householdId(), input.ownerId())
                .orElseGet(() -> new TravelProfile(
                        UUID.randomUUID(), input.householdId(), input.ownerId()));
        profile.setHomeBaseLabel(input.homeBaseLabel());
        profile.setHomeBaseLatitude(input.homeBaseLatitude());
        profile.setHomeBaseLongitude(input.homeBaseLongitude());
        profile.setRestTypes(input.restTypes());
        profile.setCompanions(input.companions());
        profile.setChildAges(input.childAges());
        profile.setBudgetAmount(input.budgetAmount());
        profile.setBudgetCurrency(input.budgetCurrency());
        profile.setNotes(input.notes());
        return profiles.save(profile).toDto();
    }

    @Tool(description = """
            Get the travel preferences for a person, treating a null `ownerId` as the household-default.
            Returns null if none have been set yet.
            """)
    @Transactional(readOnly = true)
    public TravelProfileDto getTravelProfile(UUID householdId, UUID ownerId) {
        requireField(householdId, "householdId");
        return profiles.findForOwner(householdId, ownerId)
                .map(TravelProfile::toDto)
                .orElse(null);
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
