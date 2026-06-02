package dev.fedorov.ailife.profile.web;

import dev.fedorov.ailife.contracts.profile.HouseholdDto;
import dev.fedorov.ailife.profile.domain.Household;
import dev.fedorov.ailife.profile.domain.HouseholdRepository;
import dev.fedorov.ailife.profile.web.dto.CreateHouseholdRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/households")
public class HouseholdController {

    private final HouseholdRepository repository;

    public HouseholdController(HouseholdRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<HouseholdDto> create(@Valid @RequestBody CreateHouseholdRequest request) {
        Household saved = repository.save(new Household(UUID.randomUUID(), request.name()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(toDto(saved));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HouseholdDto> get(@PathVariable UUID id) {
        return repository.findById(id)
                .map(h -> ResponseEntity.ok(toDto(h)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    static HouseholdDto toDto(Household h) {
        return new HouseholdDto(h.getId(), h.getName(), h.getCreatedAt());
    }
}
