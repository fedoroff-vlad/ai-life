package dev.fedorov.ailife.notifier.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.bus.EventBusMessage;
import dev.fedorov.ailife.contracts.notify.NotifyRequestedEvent;
import dev.fedorov.ailife.notifier.notify.NotifySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bus consumer: turns a {@code notify.requested} event into a delivered Telegram message
 * via {@link NotifySender}. Registered with the {@code EventBusListenerContainer} as the
 * listener's handler.
 *
 * <p>Retry policy is encoded by whether the handler throws (the listener leaves the outbox
 * row PENDING for the next drain) or returns (the row is marked PUBLISHED):
 * <ul>
 *   <li>Delivered (202) or a <b>permanent</b> failure (404 unknown user / 422 no telegram
 *       link / unparsable payload) → return: there is nothing to retry, and re-throwing
 *       would head-of-line-block the single-consumer drain (a known B1 limitation).</li>
 *   <li>A <b>transient</b> failure (profile/gateway 5xx, timeout, network) → throw: the row
 *       stays PENDING so the next poll redelivers it (at-least-once).</li>
 * </ul>
 *
 * <p>notifier is currently the bus's sole consumer, so it drains every topic; events other
 * than {@code notify.requested} are ignored (and thereby marked PUBLISHED).
 */
@Component
public class NotifyEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NotifyEventHandler.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final NotifySender sender;
    private final ObjectMapper json;

    public NotifyEventHandler(NotifySender sender, ObjectMapper json) {
        this.sender = sender;
        this.json = json;
    }

    public void onEvent(EventBusMessage message) {
        if (!NotifyRequestedEvent.TOPIC.equals(message.topic())) {
            return; // not ours — ignore (single-consumer bus drains all topics)
        }

        NotifyRequestedEvent event;
        try {
            event = json.readValue(message.payload(), NotifyRequestedEvent.class);
        } catch (Exception e) {
            log.warn("dropping malformed {} event {}: {}",
                    NotifyRequestedEvent.TOPIC, message.id(), e.getMessage());
            return; // permanent: a bad payload will never parse, don't retry forever
        }

        if (event.userId() == null || event.text() == null || event.text().isBlank()) {
            log.warn("dropping {} event {} with no userId/text", NotifyRequestedEvent.TOPIC, message.id());
            return; // permanent
        }

        // Blocking is fine: the listener runs the handler on its own drain thread.
        ResponseEntity<Void> outcome = sender.send(event.userId(), event.text()).block(SEND_TIMEOUT);
        HttpStatusCode status = outcome == null ? null : outcome.getStatusCode();
        if (status != null && status.is2xxSuccessful()) {
            log.debug("delivered {} to user {} (source={})",
                    NotifyRequestedEvent.TOPIC, event.userId(), event.source());
            return;
        }
        // 404 / 422 are permanent — log and accept (mark PUBLISHED) so the row isn't poisoned.
        log.warn("could not deliver {} to user {}: status={} (giving up — permanent)",
                NotifyRequestedEvent.TOPIC, event.userId(), status);
    }
}
