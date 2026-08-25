package dev.fedorov.ailife.notifier.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires {@link HeldRedrain#drain()} on a fixed delay (#487 PX-1b). Split from the logic — like
 * scheduler-service's {@code TickRunner} — so a test can drive {@code drain()} directly and deterministically.
 * Disabled by {@code notifier.held-redrain-enabled=false} (which the notifier tests set, so the real-clock
 * tick never races their fixtures); on by default in production.
 */
@Component
@ConditionalOnProperty(name = "notifier.held-redrain-enabled", havingValue = "true", matchIfMissing = true)
public class HeldRedrainRunner {

    private final HeldRedrain redrain;

    public HeldRedrainRunner(HeldRedrain redrain) {
        this.redrain = redrain;
    }

    @Scheduled(fixedDelayString = "${notifier.held-redrain-millis:60000}",
            initialDelayString = "${notifier.held-redrain-millis:60000}")
    public void tick() {
        redrain.drain();
    }
}
