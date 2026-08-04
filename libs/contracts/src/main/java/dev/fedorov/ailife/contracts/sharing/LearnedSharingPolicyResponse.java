package dev.fedorov.ailife.contracts.sharing;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.fedorov.ailife.contracts.common.SharingScope;

/**
 * The aggregate the learned-default lookup returns (ADR-0002 item 8, DS-1) — {@code GET /v1/sharing/policy}
 * on memory-service. A deterministic majority vote over the {@code memory.sharing_decision} tally for one
 * {@code (householdId, domain, signalKey)}:
 * <ul>
 *   <li>{@code scope} — the winning scope (the one the owner picked most often for this signal profile);
 *       ties break to {@link SharingScope#PRIVATE}, the safer privacy default.</li>
 *   <li>{@code confidence} — winning count / total, in {@code (0,1]}. A tie yields {@code 0.5}.</li>
 *   <li>{@code total} — total decisions recorded for this signal profile; the caller uses it as a
 *       minimum-sample guard (one data point is not a learned default).</li>
 * </ul>
 * The endpoint returns {@code 204} (no body) when the signal profile has never been seen — the caller then
 * falls back to its static {@code DefaultSharingPolicy}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LearnedSharingPolicyResponse(
        SharingScope scope,
        double confidence,
        int total) {
}
