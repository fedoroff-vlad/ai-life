package dev.fedorov.ailife.scheduler.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduleService {

    private final ScheduleRepository repo;
    private final NextRunCalculator next;
    private final ObjectMapper json;

    public ScheduleService(ScheduleRepository repo, NextRunCalculator next, ObjectMapper json) {
        this.repo = repo;
        this.next = next;
        this.json = json;
    }

    @Transactional
    public ScheduleDto create(CreateScheduleRequest req) {
        boolean hasCron = req.cron() != null && !req.cron().isBlank();
        boolean hasRunAt = req.runAt() != null;
        if (hasCron == hasRunAt) {
            throw new IllegalArgumentException("Provide exactly one of {cron, runAt}");
        }
        if (req.householdId() == null || req.ownerAgent() == null || req.kind() == null) {
            throw new IllegalArgumentException("householdId, ownerAgent, kind are required");
        }

        Instant nextRun = hasCron
                ? next.next(req.cron(), Instant.now())
                      .orElseThrow(() -> new IllegalArgumentException("Invalid cron: " + req.cron()))
                : req.runAt();

        JsonNode payload = req.payload() != null ? req.payload() : json.createObjectNode();
        Schedule s = new Schedule(
                UUID.randomUUID(),
                req.householdId(),
                req.ownerAgent(),
                req.kind(),
                hasCron ? req.cron() : null,
                payload,
                nextRun);

        return toDto(repo.save(s));
    }

    @Transactional(readOnly = true)
    public List<ScheduleDto> listByHousehold(UUID householdId) {
        return repo.findByHouseholdIdOrderByNextRunTs(householdId).stream().map(this::toDto).toList();
    }

    @Transactional
    public boolean setEnabled(UUID id, boolean enabled) {
        return repo.findById(id).map(s -> {
            s.setEnabled(enabled);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean delete(UUID id) {
        if (!repo.existsById(id)) return false;
        repo.deleteById(id);
        return true;
    }

    public ScheduleDto toDto(Schedule s) {
        return new ScheduleDto(
                s.getId(), s.getHouseholdId(), s.getOwnerAgent(), s.getKind(),
                s.getCron(), s.getPayload(), s.isEnabled(),
                s.getNextRunTs(), s.getLastRunTs(), s.getCreatedAt());
    }
}
