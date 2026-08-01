package dev.fedorov.ailife.agents.calendar.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.SharingContext;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Calendar's default-sharing rule (ADR-0002): an item the author left unscoped defaults to the shared
 * household when its categories mark it an <b>occasion</b> (birthday / anniversary / occasion — things the
 * household cares about together), and to private otherwise. This is the <b>only</b> "what is shared here"
 * logic calendar owns; the routing mechanism itself lives in {@code libs/sharing}'s {@code SharingResolver}.
 *
 * <p>The reference implementation of the {@link DefaultSharingPolicy} seam — the canonical example the
 * PATTERNS "add sharing to a domain" recipe points at. Deterministic today; the same interface can later
 * be backed by a memory-driven default without any change here or in the resolver.
 */
@Component
public class CalendarSharingPolicy implements DefaultSharingPolicy {

    /** Category tags that default an event to the shared household. */
    private static final Set<String> OCCASION_CATEGORIES = Set.of("birthday", "anniversary", "occasion");

    @Override
    public SharingScope decide(SharingContext ctx) {
        for (String c : ctx.categories()) {
            if (c != null && OCCASION_CATEGORIES.contains(c.trim().toLowerCase(Locale.ROOT))) {
                return SharingScope.SHARED;
            }
        }
        return SharingScope.PRIVATE;
    }
}
