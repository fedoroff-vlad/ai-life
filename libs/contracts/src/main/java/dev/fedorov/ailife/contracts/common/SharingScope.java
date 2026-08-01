package dev.fedorov.ailife.contracts.common;

/**
 * The privacy choice for any shareable item, cross-domain (ADR-0002 — sharing as a reusable capability;
 * lifted here from {@code contracts/calendar} where ADR-0001 introduced it for calendar). Binary by
 * design (owner-confirmed): an item lives in exactly one household, which <i>is</i> its visibility
 * boundary.
 * <ul>
 *   <li>{@code PRIVATE} → the author's <b>personal</b> household (private scope).</li>
 *   <li>{@code SHARED} → the author's <b>family</b> household (visible to its members); degrades to
 *       personal when the author has no shared household yet.</li>
 * </ul>
 * A {@code null} choice on a create-input lets the domain's {@code DefaultSharingPolicy} decide the
 * default; an explicit value always overrides it. This is a wire type — it rides in create-input JSON
 * (e.g. {@code CreateEventInput}), so it is domain-neutral and shared by every domain that opts into the
 * {@code libs/sharing} capability.
 */
public enum SharingScope {
    PRIVATE,
    SHARED
}
