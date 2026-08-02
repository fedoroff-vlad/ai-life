package dev.fedorov.ailife.profile.web;

import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.profile.domain.HouseholdMember;
import dev.fedorov.ailife.profile.domain.HouseholdMemberRepository;
import dev.fedorov.ailife.profile.domain.HouseholdRepository;
import dev.fedorov.ailife.profile.domain.User;
import dev.fedorov.ailife.profile.domain.UserRepository;
import dev.fedorov.ailife.profile.web.dto.CreateUserRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/users")
public class UserController {

    private final UserRepository users;
    private final HouseholdRepository households;
    private final HouseholdMemberRepository memberships;

    public UserController(UserRepository users, HouseholdRepository households,
                          HouseholdMemberRepository memberships) {
        this.users = users;
        this.households = households;
        this.memberships = memberships;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserRequest request) {
        if (!households.existsById(request.householdId())) {
            return ResponseEntity.unprocessableContent().build();
        }
        String locale = request.locale() != null ? request.locale() : "ru-RU";
        String role = request.role() != null ? request.role() : "member";
        User saved = users.save(new User(
                UUID.randomUUID(),
                request.householdId(),
                request.displayName(),
                locale,
                request.telegramUserId(),
                role));
        // Every user is a member of their own household (ADR-0001). The backfill covers pre-existing
        // users; this covers new ones so the membership read path is never empty for a live user.
        // Personal-household creation + approve-into-family layer on top in slice 3 (onboarding).
        memberships.save(new dev.fedorov.ailife.profile.domain.HouseholdMember(
                UUID.randomUUID(), saved.getHouseholdId(), saved.getId(), role, null));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(toDto(saved));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getById(@PathVariable UUID id) {
        return users.findById(id)
                .map(u -> ResponseEntity.ok(toDto(u)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-telegram/{telegramUserId}")
    public ResponseEntity<UserDto> getByTelegram(@PathVariable Long telegramUserId) {
        return users.findByTelegramUserId(telegramUserId)
                .map(u -> ResponseEntity.ok(toDto(u)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-household/{householdId}")
    public java.util.List<UserDto> listByHousehold(@PathVariable UUID householdId) {
        return users.findByHouseholdIdOrderByDisplayName(householdId).stream()
                .map(UserController::toDto)
                .toList();
    }

    /**
     * The caller's household set (ADR-0001 tenant routing): every household the user is a member of
     * (personal ∪ shared), ordered by join time. Downstream reads (e.g. the per-member ICS feed, #295)
     * scope to this set. 404 if the user does not exist.
     */
    @GetMapping("/{id}/households")
    public ResponseEntity<java.util.List<UUID>> households(@PathVariable UUID id) {
        if (!users.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(memberships.findByUserIdOrderByJoinedAt(id).stream()
                .map(m -> m.getHouseholdId())
                .toList());
    }

    /**
     * Tenant-routing split of the caller's memberships (ADR-0001 slice 4): the single
     * <b>personal</b> household (self-membership, {@code relationship IS NULL} — the private-scope
     * target) vs the <b>shared</b> family households ({@code relationship} set). The write path
     * (calendar-agent) resolves a private/shared choice to a concrete {@code household_id} with this.
     * 404 if the user does not exist.
     */
    @GetMapping("/{id}/household-routing")
    public ResponseEntity<HouseholdRoutingDto> householdRouting(@PathVariable UUID id) {
        if (!users.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        java.util.List<HouseholdMember> members = memberships.findByUserIdOrderByJoinedAt(id);
        UUID personal = members.stream()
                .filter(m -> m.getRelationship() == null)
                .map(HouseholdMember::getHouseholdId)
                .findFirst()
                .orElseGet(() -> members.isEmpty() ? null : members.get(0).getHouseholdId());
        java.util.List<UUID> shared = members.stream()
                .filter(m -> m.getRelationship() != null)
                .map(HouseholdMember::getHouseholdId)
                .toList();
        return ResponseEntity.ok(new HouseholdRoutingDto(personal, shared));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> duplicate(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(Map.of(
                "error", "conflict",
                "message", "violates a uniqueness or FK constraint"));
    }

    static UserDto toDto(User u) {
        return new UserDto(
                u.getId(),
                u.getHouseholdId(),
                u.getDisplayName(),
                u.getLocale(),
                u.getTelegramUserId(),
                u.getRole(),
                u.getCreatedAt());
    }
}
