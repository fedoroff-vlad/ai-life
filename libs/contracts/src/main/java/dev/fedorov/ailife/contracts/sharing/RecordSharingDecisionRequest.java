package dev.fedorov.ailife.contracts.sharing;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.fedorov.ailife.contracts.common.SharingScope;

import java.util.UUID;

/**
 * Record one resolved sharing decision into the learned-decision tally (ADR-0002 item 8, DS-1) —
 * {@code POST /v1/sharing/decisions} on memory-service. Every field is required: the {@code householdId}
 * scopes learning to the acting member's household, {@code domain} names the domain (e.g. {@code "calendar"}),
 * {@code signalKey} is the stable digest of the neutral {@code SharingContext} the caller computes (DS-2),
 * and {@code scope} is the concrete scope the resolver landed on. A repeat of the same
 * {@code (householdId, domain, signalKey, scope)} increments the count.
 *
 * <p>The store is agnostic to how {@code signalKey} is built — it just tallies opaque keys; the digest
 * logic lives caller-side in {@code libs/sharing}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecordSharingDecisionRequest(
        UUID householdId,
        String domain,
        String signalKey,
        SharingScope scope) {
}
