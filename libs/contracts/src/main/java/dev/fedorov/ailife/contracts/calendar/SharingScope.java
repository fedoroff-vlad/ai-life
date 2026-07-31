package dev.fedorov.ailife.contracts.calendar;

/**
 * The privacy choice for a calendar item (ADR-0001 slice 4, tenant routing). Binary by design
 * (owner-confirmed): an item lives in exactly one household, which <i>is</i> its visibility boundary.
 * <ul>
 *   <li>{@code PRIVATE} → the author's <b>personal</b> household (private scope).</li>
 *   <li>{@code SHARED} → the author's <b>family</b> household (visible to its members); degrades to
 *       personal when the author has no shared household yet.</li>
 * </ul>
 * A {@code null} choice on {@link CreateEventInput} lets the calendar-agent apply the default-sharing
 * policy (occasions → shared, everything else → private); an explicit value always overrides it.
 */
public enum SharingScope {
    PRIVATE,
    SHARED
}
