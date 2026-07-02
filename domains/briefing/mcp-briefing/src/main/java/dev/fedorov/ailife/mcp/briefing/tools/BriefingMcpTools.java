package dev.fedorov.ailife.mcp.briefing.tools;

import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import dev.fedorov.ailife.mcp.briefing.domain.BriefingProfile;
import dev.fedorov.ailife.mcp.briefing.domain.BriefingProfileRepository;
import dev.fedorov.ailife.mcp.briefing.scheduler.SchedulerClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
    private final SchedulerClient scheduler;

    public BriefingMcpTools(BriefingProfileRepository profiles, SchedulerClient scheduler) {
        this.profiles = profiles;
        this.scheduler = scheduler;
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
        UUID oldScheduleId = profile.getScheduleId();
        profile.setLocationLabel(input.locationLabel());
        profile.setLatitude(input.latitude());
        profile.setLongitude(input.longitude());
        profile.setTimezone(input.timezone());
        profile.setInterests(input.interests());
        profile.setSections(input.sections());
        profile.setScheduleTime(input.scheduleTime());
        profile.setScheduleEnabled(input.scheduleEnabled());
        profile.setNotes(input.notes());

        // BR-f2: keep the per-profile briefing.digest cron in sync with the
        // schedule prefs. Register the new schedule BEFORE saving so the row
        // carries the fresh id; delete the old one AFTER so a flaky scheduler
        // can't leave us cron-less (mirrors mcp-finance's set_budget). Soft-fail:
        // a null from register just means "no cron yet", the profile still saves.
        UUID newScheduleId = null;
        if (wantsSchedule(input)) {
            newScheduleId = scheduler.register(profile.getHouseholdId(), profile.getOwnerId(),
                    toUtcDailyCron(input.scheduleTime(), input.timezone()));
        }
        profile.setScheduleId(newScheduleId);
        BriefingProfileDto saved = profiles.save(profile).toDto();

        if (oldScheduleId != null && !oldScheduleId.equals(newScheduleId)) {
            scheduler.delete(oldScheduleId);
        }
        return saved;
    }

    private static boolean wantsSchedule(SetBriefingProfileInput input) {
        return Boolean.TRUE.equals(input.scheduleEnabled())
                && input.scheduleTime() != null && !input.scheduleTime().isBlank();
    }

    /**
     * Build a daily Spring cron ("0 m H * * *") from the profile's local "HH:mm"
     * wake time and IANA timezone, converted to UTC — scheduler-service's
     * {@code NextRunCalculator} evaluates cron in UTC. A null/blank timezone is
     * treated as UTC. DST caveat: a fixed-UTC cron drifts by an hour across the
     * zone's DST transitions (computed against today's offset); acceptable for
     * the MVP morning wake.
     */
    static String toUtcDailyCron(String scheduleTime, String timezone) {
        LocalTime local = LocalTime.parse(scheduleTime.trim());
        ZoneId zone = (timezone == null || timezone.isBlank())
                ? ZoneOffset.UTC : ZoneId.of(timezone.trim());
        ZonedDateTime utc = LocalDate.now(zone).atTime(local).atZone(zone)
                .withZoneSameInstant(ZoneOffset.UTC);
        return "0 " + utc.getMinute() + " " + utc.getHour() + " * * *";
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
