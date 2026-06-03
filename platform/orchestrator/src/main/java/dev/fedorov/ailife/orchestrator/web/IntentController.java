package dev.fedorov.ailife.orchestrator.web;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.orchestrator.routing.IntentRouter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/intent")
public class IntentController {

    private final IntentRouter router;

    public IntentController(IntentRouter router) {
        this.router = router;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<IntentResponse> handle(@RequestBody NormalizedMessage message) {
        return router.route(message);
    }
}
