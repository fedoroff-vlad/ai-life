package dev.fedorov.ailife.agents.briefing.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fedorov.ailife.agentruntime.http.NotifierClient;
import dev.fedorov.ailife.agentruntime.http.ProfileClient;
import dev.fedorov.ailife.agents.briefing.flow.BriefingComposer;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hit by orchestrator when scheduler-service wakes this agent (BR-f) for a {@code kind} the briefing
 * manifest declares — the same proactive path the calendar birthday wake uses. The one kind is
 * {@code briefing.digest}: the wake payload carries the target {@code ownerId} (whose {@code briefing_profile}
 * to compose), so the trigger runs the <b>same</b> {@link BriefingComposer#digest} flow the reactive
 * intent path uses (an empty user text — the composer's SKILL handles a scheduled digest) and delivers
 * the result via notifier-service.
 *
 * <p>Delivery: a personal profile ({@code ownerId} present) notifies just that user; a household-default
 * profile (no {@code ownerId}) fans out to every user in the household. Per-user notifier failures are
 * logged and don't block the others; the wake still returns 202 so the schedule advances (the wake
 * itself succeeded). No {@code ownerId} + no matching sender degrades gracefully to the household fan-out.
 */
@RestController
@RequestMapping("/agents/briefing/triggers")
public class TriggerController {

    private static final Logger log = LoggerFactory.getLogger(TriggerController.class);

    /** The one proactive trigger: compose + deliver today's digest for a scheduled profile. */
    private static final String DIGEST = "briefing.digest";

    private final BriefingComposer composer;
    private final ProfileClient profile;
    private final NotifierClient notifier;

    public TriggerController(BriefingComposer composer, ProfileClient profile, NotifierClient notifier) {
        this.composer = composer;
        this.profile = profile;
        this.notifier = notifier;
    }

    @PostMapping("/{kind}")
    public Mono<ResponseEntity<Void>> trigger(@PathVariable String kind, @RequestBody AgentWakeRequest req) {
        if (!DIGEST.equals(kind)) {
            log.warn("no trigger bound to kind={} (schedule={})", kind, req.scheduleId());
            return Mono.just(ResponseEntity.notFound().build());
        }
        UUID ownerId = ownerId(req.payload());
        // Reuse the reactive digest flow verbatim — empty user text signals a scheduled digest to the
        // composer's SKILL. ownerId (the profile owner) is the "self" so the right profile resolves.
        NormalizedMessage msg = new NormalizedMessage(
                ownerId, req.householdId(), MessageScope.PRIVATE, "",
                List.of(), "scheduler",
                req.scheduleId() == null ? null : req.scheduleId().toString(), Instant.now());
        return composer.digest(msg)
                .flatMap(resp -> deliver(req.householdId(), ownerId, resp))
                .then(Mono.just(ResponseEntity.<Void>accepted().build()));
    }

    /** Deliver to the profile owner when known, else fan out to the whole household. */
    private Mono<Void> deliver(UUID householdId, UUID ownerId, IntentResponse resp) {
        String text = resp == null ? null : resp.text();
        if (text == null || text.isBlank()) {
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

    private static UUID ownerId(JsonNode payload) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get("ownerId");
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            log.warn("payload.ownerId not a UUID: {}", node.asText());
            return null;
        }
    }
}
