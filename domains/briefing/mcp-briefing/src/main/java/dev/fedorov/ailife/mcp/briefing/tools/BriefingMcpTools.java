package dev.fedorov.ailife.mcp.briefing.tools;

import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import dev.fedorov.ailife.mcp.briefing.domain.BriefingProfile;
import dev.fedorov.ailife.mcp.briefing.domain.BriefingProfileRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Briefing domain opener (BR-b): source-of-truth CRUD over briefing.* (per-person briefing
 * preferences). The gather → synthesize digest flow lives in briefing-agent; this MCP is
 * intentionally low-level — it just persists the preferences the briefing-profiler extracts.
 *
 * Scope rule: every tool takes a householdId and reads/writes only within that household (mirrors
 * mcp-creator / mcp-nutrition). Per-person attribution is the optional ownerId (null = household-default).
 */
@Component
public class BriefingMcpTools {

    private final BriefingProfileRepository profiles;

    public BriefingMcpTools(BriefingProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Tool(description = """
            Upsert a person's briefing preferences. Keyed on (householdId, ownerId); a null ownerId is
            the household-default. `householdId` is required. This is a full set: every supplied field
            overwrites the stored value. `latitude`/`longitude` are the geocoded coordinates of
            `locationLabel` (the agent geocodes a stated city before calling); `timezone` is the
            IANA zone. `interests` (news topics) and `sections` (enabled keys: weather|agenda|finance|news)
            are free-form JSON arrays. `scheduleTime` ("HH:mm") + `scheduleEnabled` drive the morning wake.
            """)
    @Transactional
    public BriefingProfileDto setBriefingProfile(SetBriefingProfileInput input) {
        requireField(input.householdId(), "householdId");
        BriefingProfile profile = profiles.findForOwner(input.householdId(), input.ownerId())
                .orElseGet(() -> new BriefingProfile(
                        UUID.randomUUID(), input.householdId(), input.ownerId()));
        profile.setLocationLabel(input.locationLabel());
        profile.setLatitude(input.latitude());
        profile.setLongitude(input.longitude());
        profile.setTimezone(input.timezone());
        profile.setInterests(input.interests());
        profile.setSections(input.sections());
        profile.setScheduleTime(input.scheduleTime());
        profile.setScheduleEnabled(input.scheduleEnabled());
        profile.setNotes(input.notes());
        return profiles.save(profile).toDto();
    }

    @Tool(description = """
            Get the briefing preferences for a person, treating a null `ownerId` as the
            household-default. Returns null if none have been set yet.
            """)
    @Transactional(readOnly = true)
    public BriefingProfileDto getBriefingProfile(UUID householdId, UUID ownerId) {
        requireField(householdId, "householdId");
        return profiles.findForOwner(householdId, ownerId)
                .map(BriefingProfile::toDto)
                .orElse(null);
    }

    @Tool(description = """
            List every briefing profile whose morning wake is enabled, across all households. The
            scheduler reads this to fan out the per-person digests. Returns an empty list when none
            are scheduled.
            """)
    @Transactional(readOnly = true)
    public List<BriefingProfileDto> listScheduledProfiles() {
        return profiles.findByScheduleEnabledTrue().stream().map(BriefingProfile::toDto).toList();
    }

    private static void requireField(Object value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing required field: " + name);
        if (value instanceof String s && s.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + name);
        }
    }
}
