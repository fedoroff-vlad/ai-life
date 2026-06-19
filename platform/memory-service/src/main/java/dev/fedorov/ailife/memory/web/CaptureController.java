package dev.fedorov.ailife.memory.web;

import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.memory.service.CaptureService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * memory-from-chat capture endpoint (Stage 4). Takes a message, extracts durable
 * facts via the LLM, and stores them as memories — returns the written memories
 * (empty list when nothing durable was found). 400 on missing householdId / blank
 * text.
 *
 * <p>Today this is the testable core; a follow-up wires an async producer
 * (orchestrator emits a message event on the bus, memory-service consumes it) so
 * capture happens automatically off the user's request path.
 */
@RestController
public class CaptureController {

    private final CaptureService capture;

    public CaptureController(CaptureService capture) {
        this.capture = capture;
    }

    @PostMapping("/v1/capture")
    public ResponseEntity<List<MemoryDto>> capture(@RequestBody CaptureRequest req) {
        try {
            return ResponseEntity.ok(capture.capture(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
