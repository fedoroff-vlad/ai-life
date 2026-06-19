package dev.fedorov.ailife.memory.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.bus.EventBusMessage;
import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.message.MessageReceivedEvent;
import dev.fedorov.ailife.memory.service.CaptureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bus consumer: turns a {@code message.received} event into captured memories via
 * {@link CaptureService} (memory-from-chat, MFC-b). Registered with the
 * {@code EventBusListenerContainer} as the listener's handler.
 *
 * <p>Retry policy mirrors notifier's consumer — encoded by whether the handler
 * throws (the listener leaves the outbox row PENDING for the next drain) or
 * returns (the row is marked PUBLISHED):
 * <ul>
 *   <li>Captured (any number of facts, including zero) or a <b>permanent</b>
 *       failure (unparsable payload, missing householdId/text) → return: there is
 *       nothing to retry, and re-throwing would head-of-line-block the
 *       single-consumer drain.</li>
 *   <li>A <b>transient</b> failure inside the write step (embed 5xx/timeout, DB
 *       blip) → propagate: the row stays PENDING so the next poll redelivers it.</li>
 * </ul>
 *
 * <p>Note: extraction itself ({@code FactExtractor}) is best-effort and never
 * throws — an llm-gateway outage during extraction yields zero facts and the row
 * settles PUBLISHED (the message is not requeued for the reasoning step).
 *
 * <p>memory-service drains every topic (single-consumer bus); events other than
 * {@code message.received} are ignored (and thereby marked PUBLISHED).
 */
@Component
public class MessageCaptureHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageCaptureHandler.class);

    private final CaptureService capture;
    private final ObjectMapper json;

    public MessageCaptureHandler(CaptureService capture, ObjectMapper json) {
        this.capture = capture;
        this.json = json;
    }

    public void onEvent(EventBusMessage message) {
        if (!MessageReceivedEvent.TOPIC.equals(message.topic())) {
            return; // not ours — ignore (single-consumer bus drains all topics)
        }

        MessageReceivedEvent event;
        try {
            event = json.readValue(message.payload(), MessageReceivedEvent.class);
        } catch (Exception e) {
            log.warn("dropping malformed {} event {}: {}",
                    MessageReceivedEvent.TOPIC, message.id(), e.getMessage());
            return; // permanent: a bad payload will never parse
        }

        if (event.householdId() == null || event.text() == null || event.text().isBlank()) {
            log.debug("ignoring {} event {} with no householdId/text",
                    MessageReceivedEvent.TOPIC, message.id());
            return; // nothing to capture
        }

        try {
            List<MemoryDto> written = capture.capture(new CaptureRequest(
                    event.householdId(), event.userId(), null, event.text()));
            log.debug("captured {} memories from message (source={})", written.size(), event.source());
        } catch (IllegalArgumentException e) {
            // Validation already guarded above — treat as permanent and accept.
            log.warn("dropping {} event {}: {}", MessageReceivedEvent.TOPIC, message.id(), e.getMessage());
        }
        // Any other exception (embed/db) propagates → row stays PENDING → retried.
    }
}
