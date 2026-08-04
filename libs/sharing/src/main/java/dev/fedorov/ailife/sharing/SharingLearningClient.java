package dev.fedorov.ailife.sharing;

import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.sharing.LearnedSharingPolicyResponse;
import dev.fedorov.ailife.contracts.sharing.RecordSharingDecisionRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The thin read/write over memory-service's learned-decision tally (ADR-0002 item 8, DS-1 store). It is the
 * one client both the {@link SharingResolver} (write — record the owner's explicit choice) and a
 * {@link LearnedSharingPolicy} (read — the learned default) call, mirroring {@link ProfileSharingClient}: the
 * consumer owns the memory-service-base-URL-bound {@link WebClient}; this class is just the request shape.
 *
 * <p><b>Best-effort on both sides.</b> Learning must never delay or fail a create: {@link #record} swallows
 * every error, and {@link #policy} resolves to {@link Mono#empty()} on a {@code 204} (signal profile unseen)
 * or any error, so the caller falls back to its static {@link DefaultSharingPolicy}.
 */
public class SharingLearningClient {

    private final WebClient http;

    public SharingLearningClient(WebClient memoryServiceWebClient) {
        this.http = memoryServiceWebClient;
    }

    /** Record one resolved decision into the tally; best-effort (all errors swallowed). */
    public Mono<Void> record(UUID householdId, String domain, String signalKey, SharingScope scope) {
        return http.post()
                .uri("/v1/sharing/decisions")
                .bodyValue(new RecordSharingDecisionRequest(householdId, domain, signalKey, scope))
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * The learned default for a signal profile. {@link Mono#empty()} when the profile is unseen ({@code 204},
     * no body) or on any error → the caller uses its static policy.
     */
    public Mono<LearnedSharingPolicyResponse> policy(UUID householdId, String domain, String signalKey) {
        return http.get()
                .uri(uri -> uri.path("/v1/sharing/policy")
                        .queryParam("householdId", householdId)
                        .queryParam("domain", domain)
                        .queryParam("signalKey", signalKey)
                        .build())
                .retrieve()
                .bodyToMono(LearnedSharingPolicyResponse.class)
                .onErrorResume(e -> Mono.empty());
    }
}
