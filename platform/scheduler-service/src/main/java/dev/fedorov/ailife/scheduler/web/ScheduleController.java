package dev.fedorov.ailife.scheduler.web;

import dev.fedorov.ailife.contracts.schedule.CreateScheduleRequest;
import dev.fedorov.ailife.contracts.schedule.ScheduleDto;
import dev.fedorov.ailife.scheduler.domain.ScheduleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateScheduleRequest req) {
        try {
            ScheduleDto dto = service.create(req);
            return ResponseEntity.created(URI.create("/v1/schedules/" + dto.id())).body(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<ScheduleDto> list(@RequestParam UUID householdId) {
        return service.listByHousehold(householdId);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pause(@PathVariable UUID id) {
        return service.setEnabled(id, false)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Void> resume(@PathVariable UUID id) {
        return service.setEnabled(id, true)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return service.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
