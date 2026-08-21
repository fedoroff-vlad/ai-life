package dev.fedorov.ailife.agents.calendar.web;

import dev.fedorov.ailife.agents.calendar.intent.CalendarIntentRouter;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code calendar}. Delegates to
 * {@link CalendarIntentRouter}, which classifies the message (via the shared {@code SkillClassifier}, #475)
 * into one of the agent's user-facing event flows — capture (Track H.2 / HC-1) — or a plain chat reply
 * ({@code CalendarChat}, which keeps the #195 ICS-feed nudge). Before HC-1 this controller answered every
 * message with a single LLM chat call; the routing + the chat/feed logic now live in the router + chat
 * fallback.
 */
@RestController
@RequestMapping("/agents/calendar")
public class IntentController {

    private final CalendarIntentRouter router;

    public IntentController(CalendarIntentRouter router) {
        this.router = router;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        return router.route(message);
    }
}
