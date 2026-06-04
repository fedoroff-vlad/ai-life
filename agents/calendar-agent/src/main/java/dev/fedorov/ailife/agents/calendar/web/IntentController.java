package dev.fedorov.ailife.agents.calendar.web;

import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hit by orchestrator when intent routing selects {@code calendar}. PR9b stub —
 * ack-only; real handling (parse datetime, call mcp-caldav, etc.) lands in PR9c
 * alongside the intent classifier.
 */
@RestController
@RequestMapping("/agents/calendar")
public class IntentController {

    private static final Logger log = LoggerFactory.getLogger(IntentController.class);

    @PostMapping("/intent")
    public ResponseEntity<Void> intent(@RequestBody NormalizedMessage message) {
        log.info("intent stub: userId={} scope={} text='{}'",
                message.userId(), message.scope(), message.text());
        return ResponseEntity.accepted().build();
    }
}
