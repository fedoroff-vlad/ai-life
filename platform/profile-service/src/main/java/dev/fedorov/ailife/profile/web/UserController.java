package dev.fedorov.ailife.profile.web;

import dev.fedorov.ailife.contracts.profile.UserDto;
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

    public UserController(UserRepository users, HouseholdRepository households) {
        this.users = users;
        this.households = households;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserRequest request) {
        if (!households.existsById(request.householdId())) {
            return ResponseEntity.unprocessableEntity().build();
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
