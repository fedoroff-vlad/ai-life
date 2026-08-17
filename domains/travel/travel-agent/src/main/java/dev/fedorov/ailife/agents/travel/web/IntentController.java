package dev.fedorov.ailife.agents.travel.web;

import dev.fedorov.ailife.agents.travel.flow.RouteFlow;
import dev.fedorov.ailife.agents.travel.intent.TravelIntentRouter;
import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Hit by the orchestrator when intent routing selects {@code travel}:
 * <ul>
 *   <li>a route file attached to the message → {@link RouteFlow#handle} (RT-c) — an unambiguous route/
 *       itinerary import (the flow sniffs the format and soft-fails if it isn't a route file);</li>
 *   <li>otherwise → {@link TravelIntentRouter}, which classifies the text (via the shared
 *       {@code SkillClassifier}) into one of the four text-intent flows — travel preferences / trip wallet /
 *       packing list / plan a trip — or, for a bare map link, a route-link import, else a plain chat reply.</li>
 * </ul>
 * The attachment check comes first (a file is unambiguously an import, not a text intent, so it stays a
 * deterministic pre-check rather than an LLM classification, like docs' photo pre-check). The typed-message
 * keyword-cue heuristic this controller used to carry ({@code PROFILE_CUES}/{@code WALLET_CUES}/{@code
 * PACKING_CUES}/{@code PLAN_CUES}) was replaced by the LLM classifier in skills-vs-flows Bucket 1 (#475), so
 * the routing SSOT is each intent skill's SKILL.md description; the map-link import folds into the router's
 * chat fallback ({@link TravelIntentRouter}).
 */
@RestController
@RequestMapping("/agents/travel")
public class IntentController {

    private final RouteFlow route;
    private final TravelIntentRouter router;

    public IntentController(RouteFlow route, TravelIntentRouter router) {
        this.route = route;
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        Optional<Attachment> routeFile = routeFileAttachment(message);
        if (routeFile.isPresent()) {
            return route.handle(message, routeFile.get());
        }
        return router.route(message);
    }

    private static Optional<Attachment> routeFileAttachment(NormalizedMessage message) {
        if (message == null || message.attachments() == null) {
            return Optional.empty();
        }
        return message.attachments().stream()
                .filter(a -> "file".equals(a.kind()) && a.storageUri() != null)
                .findFirst();
    }
}
