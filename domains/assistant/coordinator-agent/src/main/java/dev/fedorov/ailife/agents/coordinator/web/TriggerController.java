package dev.fedorov.ailife.agents.coordinator.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.agentruntime.coordinate.CoordinationResult;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agents.coordinator.flow.MultiDomainCoordinator;
import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The proactive path (#290, Slice A): scheduler-service wakes the coordinator to <b>surface</b> a useful
 * connection or idea from the second brain, unprompted. The one bound kind is {@code coordinator.surface};
 * the payload may carry {@code {ownerId}} (whom to deliver to) and {@code {focus}} (a topic hint).
 *
 * <p>Precision over volume: the synthesized surface is delivered via notifier-service <b>only when it
 * clears {@link MultiDomainCoordinator#isWorthSurfacing the relevance gate}</b> — a thin/empty result is
 * dropped rather than sent as noise. A surface is a <i>proposal</i>, so pushing it needs no confirmation
 * ("propose freely, act outward only on confirm"). Delivery mirrors the briefing agent: a known owner is
 * notified directly, otherwise it fans out to the household. The wake still returns 202 once the synthesis
 * ran, so the schedule advances even when nothing was worth surfacing.
 */
@RestController
@RequestMapping("/agents/coordinator/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    private static final String SURFACE = "coordinator.surface";

    private final MultiDomainCoordinator coordinator;
    private final ProfileClient profile;
    private final NotifierClient notifier;

    public TriggerController(MultiDomainCoordinator coordinator, ProfileClient profile,
                             NotifierClient notifier) {
        this.coordinator = coordinator;
        this.profile = profile;
        this.notifier = notifier;
    }

    @PostMapping("/{kind}")
    public Mono<ResponseEntity<Void>> trigger(@PathVariable String kind, @RequestBody AgentWakeRequest req) {
        if (!SURFACE.equals(kind)) {
            log.warn("no trigger bound to kind={} (schedule={})", kind, req.scheduleId());
            return Mono.just(ResponseEntity.notFound().build());
        }
        UUID ownerId = uuid(req.payload(), "ownerId");
        String focus = text(req.payload(), "focus");
        return coordinator.surface(req.householdId(), ownerId, focus)
                .flatMap(result -> deliver(req.householdId(), ownerId, result))
                .then(Mono.just(ResponseEntity.<Void>accepted().build()));
    }

    /** Deliver only a surface worth the owner's attention; to the owner when known, else the household. */
    private Mono<Void> deliver(UUID householdId, UUID ownerId, CoordinationResult result) {
        String text = result == null ? null : result.text();
        if (!MultiDomainCoordinator.isWorthSurfacing(text)) {
            log.info("nothing worth surfacing (household={}) — staying silent", householdId);
            return Mono.empty();
        }
        if (ownerId != null) {
            return notifyOne(ownerId, text);
        }
        if (householdId == null) {
            return Mono.empty();
        }
        return profile.usersByHousehold(householdId)
                .flatMap(u -> notifyOne(u.id(), text))
                .then();
    }

    private Mono<Void> notifyOne(UUID userId, String text) {
        return notifier.notify(userId, text)
                .doOnError(e -> log.warn("notify failed for user={}: {}", userId, e.toString()))
                .onErrorResume(e -> Mono.empty());
    }

    private static UUID uuid(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            log.warn("payload.{} not a UUID: {}", field, node.asText());
            return null;
        }
    }

    private static String text(JsonNode payload, String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get(field);
        return (node == null || node.isNull()) ? null : node.asText();
    }
}
