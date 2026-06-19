package dev.fedorov.ailife.memory.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.bus.OutboxPublisher;
import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.message.MessageReceivedEvent;
import dev.fedorov.ailife.memory.service.CaptureService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * memory-from-chat ingress (Stage 4). Two ways in:
 *
 * <ul>
 *   <li><b>{@code POST /v1/capture}</b> — synchronous: extract durable facts via
 *       the LLM and store them now, returning the written memories. Useful for
 *       direct/manual/debug use and tests. 400 on missing householdId / blank text.</li>
 *   <li><b>{@code POST /v1/observations}</b> — the durable async <b>drop-point</b> for
 *       DB-less producers (orchestrator, agents): one HTTP call enqueues a
 *       {@link MessageReceivedEvent} onto the event bus ({@code bus.outbox}) and
 *       returns 202. The expensive LLM extraction runs later on the consumer
 *       ({@code MessageCaptureHandler}, MFC-b), with at-least-once retry. This is
 *       the "easy push" any component uses without owning a DB — durable, structured
 *       (not a cache that turns into a dump), and on infra we already run.</li>
 * </ul>
 */
@RestController
public class CaptureController {

    private final CaptureService capture;
    private final OutboxPublisher outbox;
    private final ObjectMapper json;

    public CaptureController(CaptureService capture, OutboxPublisher outbox, ObjectMapper json) {
        this.capture = capture;
        this.outbox = outbox;
        this.json = json;
    }

    @PostMapping("/v1/capture")
    public ResponseEntity<List<MemoryDto>> capture(@RequestBody CaptureRequest req) {
        try {
            return ResponseEntity.ok(capture.capture(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/v1/observations")
    public ResponseEntity<Void> observe(@RequestBody MessageReceivedEvent event) {
        if (event.householdId() == null || event.text() == null || event.text().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().build();
        }
        outbox.publish(MessageReceivedEvent.TOPIC, event.householdId(), payload);
        return ResponseEntity.accepted().build();
    }
}
