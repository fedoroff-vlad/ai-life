package dev.fedorov.ailife.memory.web;

import dev.fedorov.ailife.contracts.sharing.LearnedSharingPolicyResponse;
import dev.fedorov.ailife.contracts.sharing.RecordSharingDecisionRequest;
import dev.fedorov.ailife.memory.service.SharingDecisionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The learned-decision tally behind memory-driven default-sharing (ADR-0002 item 8, DS-1). {@code POST
 * /v1/sharing/decisions} records a resolved sharing decision; {@code GET /v1/sharing/policy} returns the
 * learned default for a signal profile (a deterministic majority vote), or {@code 204} when unseen so the
 * caller falls back to its static per-domain policy. The store is domain-agnostic — it tallies opaque
 * {@code signalKey}s built caller-side in {@code libs/sharing}.
 */
@RestController
@RequestMapping(path = "/v1/sharing", produces = MediaType.APPLICATION_JSON_VALUE)
public class SharingController {

    private final SharingDecisionService service;

    public SharingController(SharingDecisionService service) {
        this.service = service;
    }

    @PostMapping(path = "/decisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> record(@RequestBody RecordSharingDecisionRequest req) {
        service.record(req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/policy")
    public ResponseEntity<LearnedSharingPolicyResponse> policy(@RequestParam("householdId") UUID householdId,
                                                              @RequestParam("domain") String domain,
                                                              @RequestParam("signalKey") String signalKey) {
        return service.learnedPolicy(householdId, domain, signalKey)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
