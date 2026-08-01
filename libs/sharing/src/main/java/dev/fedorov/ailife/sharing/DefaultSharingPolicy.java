package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;

/**
 * The per-domain extension point of the sharing capability (ADR-0002): given the neutral
 * {@link SharingContext}, decide the <b>default</b> privacy of an item when the author made no explicit
 * choice. Implemented once per domain, in that domain's {@code sharing/} package (e.g.
 * {@code CalendarSharingPolicy}: occasions → shared, everything else → private; {@code FinanceSharingPolicy}:
 * joint-account → shared, else private).
 *
 * <p>This is the <b>only</b> seam where "what is shared here" lives — the routing mechanism itself
 * ({@link SharingResolver}) is shared and deterministic. Today implementations plug a static
 * deterministic rule; later the <i>same interface</i> can be backed by a memory/second-brain-driven
 * implementation (learn the owner's past choices, confirm only on ambiguity — ADR-0001 item 7) without
 * touching {@link SharingResolver} or any domain.
 */
@FunctionalInterface
public interface DefaultSharingPolicy {

    /** The default scope for an item with no explicit choice. Never returns {@code null}. */
    SharingScope decide(SharingContext ctx);

    /** A policy that defaults everything to private — the safe fallback for a domain with no rule yet. */
    static DefaultSharingPolicy privateByDefault() {
        return ctx -> SharingScope.PRIVATE;
    }
}
