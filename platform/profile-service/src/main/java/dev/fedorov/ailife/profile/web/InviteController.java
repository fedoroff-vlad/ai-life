package dev.fedorov.ailife.profile.web;

import dev.fedorov.ailife.contracts.profile.HouseholdInviteDto;
import dev.fedorov.ailife.profile.domain.HouseholdInvite;
import dev.fedorov.ailife.profile.domain.HouseholdInviteRepository;
import dev.fedorov.ailife.profile.domain.HouseholdMember;
import dev.fedorov.ailife.profile.domain.HouseholdMemberRepository;
import dev.fedorov.ailife.profile.domain.HouseholdRepository;
import dev.fedorov.ailife.profile.domain.UserRepository;
import dev.fedorov.ailife.profile.web.dto.MintInviteRequest;
import dev.fedorov.ailife.profile.web.dto.RedeemInviteRequest;
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
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Household invites (ADR-0001, slice 4a). The owner mints a pre-authorized invite; the invitee
 * redeems it on first contact, which inserts their membership into the family household. The
 * Telegram deep-link {@code /start <token>} wiring + owner command live in gateway-telegram (4b).
 */
@RestController
@RequestMapping("/v1/invites")
public class InviteController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKENS = Base64.getUrlEncoder().withoutPadding();

    private final HouseholdInviteRepository invites;
    private final HouseholdRepository households;
    private final UserRepository users;
    private final HouseholdMemberRepository memberships;

    public InviteController(HouseholdInviteRepository invites, HouseholdRepository households,
                            UserRepository users, HouseholdMemberRepository memberships) {
        this.invites = invites;
        this.households = households;
        this.users = users;
        this.memberships = memberships;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<HouseholdInviteDto> mint(@Valid @RequestBody MintInviteRequest request) {
        if (!households.existsById(request.familyHouseholdId())
                || !users.existsById(request.inviterUserId())) {
            return ResponseEntity.unprocessableEntity().build();
        }
        boolean grant = request.grantSharedAccess() == null || request.grantSharedAccess();
        HouseholdInvite saved = invites.save(new HouseholdInvite(
                UUID.randomUUID(),
                newToken(),
                request.familyHouseholdId(),
                request.inviterUserId(),
                request.relationship(),
                grant));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/by-token/{token}")
                .buildAndExpand(saved.getToken())
                .toUri();
        return ResponseEntity.created(location).body(toDto(saved));
    }

    @GetMapping("/by-token/{token}")
    public ResponseEntity<HouseholdInviteDto> getByToken(@PathVariable String token) {
        return invites.findByToken(token)
                .map(i -> ResponseEntity.ok(toDto(i)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Redeem a pending invite for an already-created invitee. Inserts the invitee into the family
     * household (when share-access was granted) and flips the invite to {@code accepted}. Idempotent
     * on the membership. 404 unknown token · 422 unknown invitee · 409 already redeemed/revoked.
     */
    @PostMapping("/by-token/{token}/redeem")
    @Transactional
    public ResponseEntity<HouseholdInviteDto> redeem(@PathVariable String token,
                                                     @Valid @RequestBody RedeemInviteRequest request) {
        HouseholdInvite invite = invites.findByToken(token).orElse(null);
        if (invite == null) {
            return ResponseEntity.notFound().build();
        }
        if (!invite.isPending()) {
            return ResponseEntity.status(409).build();
        }
        if (!users.existsById(request.inviteeUserId())) {
            return ResponseEntity.unprocessableEntity().build();
        }
        if (invite.isGrantSharedAccess()
                && !memberships.existsByHouseholdIdAndUserId(
                        invite.getFamilyHouseholdId(), request.inviteeUserId())) {
            memberships.save(new HouseholdMember(
                    UUID.randomUUID(),
                    invite.getFamilyHouseholdId(),
                    request.inviteeUserId(),
                    "member",
                    invite.getRelationship()));
        }
        invite.accept(request.inviteeUserId());
        return ResponseEntity.ok(toDto(invite));
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return TOKENS.encodeToString(bytes);
    }

    static HouseholdInviteDto toDto(HouseholdInvite i) {
        return new HouseholdInviteDto(
                i.getId(),
                i.getToken(),
                i.getFamilyHouseholdId(),
                i.getInviterUserId(),
                i.getRelationship(),
                i.isGrantSharedAccess(),
                i.getStatus(),
                i.getInviteeUserId(),
                i.getCreatedAt(),
                i.getAcceptedAt());
    }
}
