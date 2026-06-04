package dev.fedorov.ailife.scheduler.tick;

import dev.fedorov.ailife.contracts.schedule.AgentWakeRequest;
import dev.fedorov.ailife.scheduler.config.SchedulerProperties;
import dev.fedorov.ailife.scheduler.domain.NextRunCalculator;
import dev.fedorov.ailife.scheduler.domain.Schedule;
import dev.fedorov.ailife.scheduler.domain.ScheduleRepository;
import dev.fedorov.ailife.scheduler.orchestrator.OrchestratorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Tick logic in isolation. Schedule firing is owned by {@link TickRunner}
 * (production) — keeping these split means tests can disable the auto-tick
 * entirely while still invoking the logic directly.
 */
@Component
public class ScheduleTick {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTick.class);

    private final ScheduleRepository repo;
    private final OrchestratorClient orchestrator;
    private final NextRunCalculator next;
    private final SchedulerProperties props;
    private final TransactionTemplate tx;

    public ScheduleTick(ScheduleRepository repo,
                        OrchestratorClient orchestrator,
                        NextRunCalculator next,
                        SchedulerProperties props,
                        TransactionTemplate tx) {
        this.repo = repo;
        this.orchestrator = orchestrator;
        this.next = next;
        this.props = props;
        this.tx = tx;
    }

    public void tick() {
        Instant now = Instant.now();
        List<Schedule> due = tx.execute(s -> repo.findDue(now, PageRequest.of(0, props.getBatchSize())));
        if (due == null || due.isEmpty()) {
            return;
        }
        log.debug("tick: {} due", due.size());
        for (Schedule s : due) {
            try {
                orchestrator.wake(new AgentWakeRequest(
                        s.getId(), s.getHouseholdId(),
                        s.getOwnerAgent(), s.getKind(), s.getPayload()));
                advance(s.getId(), now);
            } catch (RuntimeException e) {
                log.warn("wake failed for schedule {} — left in due state", s.getId(), e);
            }
        }
    }

    /** Each schedule advance is its own tx so failures are isolated. */
    void advance(java.util.UUID id, Instant ranAt) {
        tx.executeWithoutResult(status -> repo.findById(id).ifPresent(s -> {
            s.setLastRunTs(ranAt);
            if (s.getCron() != null) {
                next.next(s.getCron(), ranAt).ifPresentOrElse(
                        s::setNextRunTs,
                        () -> s.setEnabled(false));
            } else {
                s.setEnabled(false);
            }
        }));
    }
}
