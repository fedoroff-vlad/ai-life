package dev.fedorov.ailife.conversation.web;

import dev.fedorov.ailife.contracts.conversation.ConversationStateDto;
import dev.fedorov.ailife.contracts.conversation.SetConversationStateRequest;
import dev.fedorov.ailife.conversation.domain.ConversationStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for short-term conversation control state. Internal-only by convention (called by
 * orchestrator before classifying, and by agents to set/clear a pending confirmation). No auth —
 * same posture as the other platform stores.
 *
 * <ul>
 *   <li>{@code PUT /v1/conversation-state} — upsert the lock + pending action (TTL'd).</li>
 *   <li>{@code GET /v1/conversation-state?householdId=&userId=&channel=} — the active (unexpired)
 *       state, or 204 when none.</li>
 *   <li>{@code DELETE /v1/conversation-state?householdId=&userId=&channel=} — clear after resolve.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/conversation-state")
public class ConversationStateController {

    private final ConversationStateService service;

    public ConversationStateController(ConversationStateService service) {
        this.service = service;
    }

    @PutMapping
    public ConversationStateDto set(@RequestBody SetConversationStateRequest request) {
        return service.set(request);
    }

    @GetMapping
    public ResponseEntity<ConversationStateDto> get(@RequestParam UUID householdId,
                                                    @RequestParam UUID userId,
                                                    @RequestParam String channel) {
        return service.getActive(householdId, userId, channel)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@RequestParam UUID householdId,
                                      @RequestParam UUID userId,
                                      @RequestParam String channel) {
        service.clear(householdId, userId, channel);
        return ResponseEntity.noContent().build();
    }
}
