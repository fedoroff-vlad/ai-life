package dev.fedorov.ailife.scheduler.tick;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Production-side fixed-delay runner around {@link ScheduleTick}. Off by setting
 * {@code scheduler.auto-tick=false} (tests do this so they can invoke the tick
 * deterministically without a background thread interfering).
 */
@Component
@ConditionalOnProperty(prefix = "scheduler", name = "auto-tick", havingValue = "true", matchIfMissing = true)
public class TickRunner {

    private final ScheduleTick tick;

    public TickRunner(ScheduleTick tick) {
        this.tick = tick;
    }

    @Scheduled(fixedDelayString = "${scheduler.tick-millis:30000}")
    @SchedulerLock(name = "scheduler-tick", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void runTick() {
        tick.tick();
    }
}
