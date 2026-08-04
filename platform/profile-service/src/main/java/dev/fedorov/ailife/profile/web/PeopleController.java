package dev.fedorov.ailife.profile.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.profile.PersonDto;
import dev.fedorov.ailife.profile.domain.HouseholdRepository;
import dev.fedorov.ailife.profile.domain.Person;
import dev.fedorov.ailife.profile.domain.PersonRepository;
import dev.fedorov.ailife.profile.domain.UserRepository;
import dev.fedorov.ailife.profile.web.dto.CreatePersonRequest;
import dev.fedorov.ailife.profile.web.dto.LinkPersonUserRequest;
import dev.fedorov.ailife.profile.web.dto.UpdatePersonRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/people")
public class PeopleController {

    private final PersonRepository people;
    private final HouseholdRepository households;
    private final UserRepository users;
    private final ObjectMapper json;

    public PeopleController(PersonRepository people, HouseholdRepository households,
                           UserRepository users, ObjectMapper json) {
        this.people = people;
        this.households = households;
        this.users = users;
        this.json = json;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<PersonDto> create(@Valid @RequestBody CreatePersonRequest request) {
        if (!households.existsById(request.householdId())) {
            return ResponseEntity.unprocessableContent().build();
        }
        JsonNode interests = request.interests() != null ? request.interests() : json.createArrayNode();
        Person saved = people.save(new Person(
                UUID.randomUUID(),
                request.householdId(),
                request.displayName(),
                request.relationship(),
                request.locale(),
                interests,
                request.notes(),
                request.leadDaysOverride()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(toDto(saved));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PersonDto> get(@PathVariable UUID id) {
        return people.findById(id)
                .map(p -> ResponseEntity.ok(toDto(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-household/{householdId}")
    public List<PersonDto> listByHousehold(@PathVariable UUID householdId) {
        return people.findByHouseholdIdOrderByDisplayName(householdId).stream()
                .map(PeopleController::toDto)
                .toList();
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<PersonDto> update(@PathVariable UUID id, @Valid @RequestBody UpdatePersonRequest req) {
        return people.findById(id).map(p -> {
            if (req.displayName() != null) p.setDisplayName(req.displayName());
            if (req.relationship() != null) p.setRelationship(req.relationship());
            if (req.locale() != null) p.setLocale(req.locale());
            if (req.interests() != null) p.setInterests(req.interests());
            if (req.notes() != null) p.setNotes(req.notes());
            if (req.leadDaysOverride() != null) p.setLeadDaysOverride(req.leadDaysOverride());
            return ResponseEntity.ok(toDto(p));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Link a contact to the operator it became (ADR-0001 item 6) — e.g. a child who starts using the
     * bot. Idempotent set (also unlink/re-link). 404 if the person is unknown; 422 if the {@code userId}
     * references no existing user. The person's household and prior notes/events are left untouched.
     */
    @PutMapping("/{id}/user")
    @Transactional
    public ResponseEntity<PersonDto> linkUser(@PathVariable UUID id, @Valid @RequestBody LinkPersonUserRequest req) {
        if (!users.existsById(req.userId())) {
            return ResponseEntity.unprocessableContent().build();
        }
        return people.findById(id).map(p -> {
            p.setUserId(req.userId());
            return ResponseEntity.ok(toDto(p));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    static PersonDto toDto(Person p) {
        return new PersonDto(
                p.getId(), p.getHouseholdId(), p.getDisplayName(), p.getRelationship(),
                p.getLocale(), p.getInterests(), p.getNotes(), p.getLeadDaysOverride(),
                p.getCreatedAt(), p.getUserId());
    }
}
