package dev.fedorov.ailife.tg.identity;

import dev.fedorov.ailife.contracts.profile.HouseholdDto;
import dev.fedorov.ailife.contracts.profile.HouseholdInviteDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.tg.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-level identity resolution (ADR-0001, slice 3). A new Telegram user must get their own
 * personal household named after them; a returning user is reused without any creation call.
 */
class IdentityResolverTest {

    private final ProfileClient profile = mock(ProfileClient.class);
    private final GatewayProperties props = new GatewayProperties();
    private final IdentityResolver resolver = new IdentityResolver(profile, props);

    @Test
    void newUserGetsPersonalHouseholdNamedAfterThem() {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserDto created = new UserDto(userId, householdId, "vlad", "ru-RU", 42L, "admin", Instant.now());

        when(profile.findByTelegramId(42L)).thenReturn(Mono.empty());
        when(profile.createHousehold("vlad"))
                .thenReturn(Mono.just(new HouseholdDto(householdId, "vlad", Instant.now())));
        when(profile.createUser(eq(householdId.toString()), eq("vlad"), eq(42L), eq("ru"), eq("admin")))
                .thenReturn(Mono.just(created));

        UserDto result = resolver.resolve(42L, "vlad", "ru").block();

        assertThat(result).isEqualTo(created);
        verify(profile).createHousehold("vlad"); // personal household, not the shared default
    }

    @Test
    void unnamedUserFallsBackToDefaultHouseholdName() {
        UUID householdId = UUID.randomUUID();
        when(profile.findByTelegramId(7L)).thenReturn(Mono.empty());
        when(profile.createHousehold(props.getTelegram().getDefaultHouseholdName()))
                .thenReturn(Mono.just(new HouseholdDto(householdId, "default household", Instant.now())));
        when(profile.createUser(eq(householdId.toString()), eq("user-7"), eq(7L), anyString(), eq("admin")))
                .thenReturn(Mono.just(new UserDto(
                        UUID.randomUUID(), householdId, "user-7", "ru-RU", 7L, "admin", Instant.now())));

        resolver.resolve(7L, null, null).block();

        verify(profile).createHousehold(props.getTelegram().getDefaultHouseholdName());
    }

    @Test
    void returningUserIsReusedWithoutCreation() {
        UserDto existing = new UserDto(
                UUID.randomUUID(), UUID.randomUUID(), "vlad", "ru-RU", 99L, "admin", Instant.now());
        when(profile.findByTelegramId(99L)).thenReturn(Mono.just(existing));

        UserDto result = resolver.resolve(99L, "vlad", "ru").block();

        assertThat(result).isEqualTo(existing);
        verify(profile).findByTelegramId(99L);
        verifyNoMoreInteractions(profile); // no household/user creation for a known user
    }

    @Test
    void invitedUserJoinsAndHolderIsNamedForThePing() {
        UUID inviteeId = UUID.randomUUID();
        UUID inviterId = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        UserDto invitee = new UserDto(inviteeId, UUID.randomUUID(), "Masha", "ru-RU", 555L, "member", Instant.now());
        UserDto inviter = new UserDto(inviterId, family, "vlad", "ru-RU", 42L, "admin", Instant.now());

        when(profile.findByTelegramId(555L)).thenReturn(Mono.just(invitee));
        when(profile.redeem("tok", inviteeId.toString())).thenReturn(Mono.just(new HouseholdInviteDto(
                UUID.randomUUID(), "tok", family, inviterId, "daughter", true,
                "accepted", inviteeId, Instant.now(), Instant.now())));
        when(profile.findById(inviterId.toString())).thenReturn(Mono.just(inviter));

        InviteOutcome outcome = resolver.redeemInvite(555L, "Masha", "ru", "tok").block();

        assertThat(outcome).isNotNull();
        // #490 FO-1: the join reply orients the new member — relationship + what works now + how to
        // set personal preferences in chat, not a bare confirmation.
        assertThat(outcome.inviteeReply())
                .contains("daughter")
                .contains("брифинг")
                .contains("я встаю в 7");
        assertThat(outcome.holderTelegramId()).isEqualTo(42L);
        assertThat(outcome.holderReply()).contains("Masha").contains("daughter");
    }

    @Test
    void joinReplyOrientsAnEnglishMemberEvenWithoutARelationship() {
        UUID inviteeId = UUID.randomUUID();
        UUID inviterId = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        UserDto invitee = new UserDto(inviteeId, UUID.randomUUID(), "Kate", "en-GB", 556L, "member", Instant.now());
        UserDto inviter = new UserDto(inviterId, family, "vlad", "ru-RU", 42L, "admin", Instant.now());

        when(profile.findByTelegramId(556L)).thenReturn(Mono.just(invitee));
        when(profile.redeem("tok", inviteeId.toString())).thenReturn(Mono.just(new HouseholdInviteDto(
                UUID.randomUUID(), "tok", family, inviterId, null, true,
                "accepted", inviteeId, Instant.now(), Instant.now())));
        when(profile.findById(inviterId.toString())).thenReturn(Mono.just(inviter));

        InviteOutcome outcome = resolver.redeemInvite(556L, "Kate", "en", "tok").block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.inviteeReply())
                .contains("family space")
                .contains("briefing")
                .contains("no setup needed");
    }

    @Test
    void ownerMintsInviteIntoTheirHouseholdAndGetsADeepLink() {
        UUID ownerId = UUID.randomUUID();
        UUID household = UUID.randomUUID();
        UserDto owner = new UserDto(ownerId, household, "vlad", "ru-RU", 42L, "admin", Instant.now());

        when(profile.findByTelegramId(42L)).thenReturn(Mono.just(owner));
        when(profile.mintInvite(household.toString(), ownerId.toString(), "daughter"))
                .thenReturn(Mono.just(new HouseholdInviteDto(
                        UUID.randomUUID(), "TOK123", household, ownerId, "daughter", true,
                        "pending", null, Instant.now(), null)));

        String reply = resolver.mintInvite(42L, "vlad", "ru", "Masha", "daughter").block();

        assertThat(reply).contains("https://t.me/ai_life_bot?start=TOK123");
        assertThat(reply).contains("Masha").contains("daughter");
        verify(profile).mintInvite(household.toString(), ownerId.toString(), "daughter");
    }

    @Test
    void unknownOrUsedTokenYieldsGracefulNoJoinReplyWithoutHolderLookup() {
        UUID inviteeId = UUID.randomUUID();
        UserDto invitee = new UserDto(inviteeId, UUID.randomUUID(), "Masha", "ru-RU", 555L, "member", Instant.now());

        when(profile.findByTelegramId(555L)).thenReturn(Mono.just(invitee));
        when(profile.redeem("badtoken", inviteeId.toString())).thenReturn(Mono.error(
                WebClientResponseException.create(409, "Conflict", HttpHeaders.EMPTY, new byte[0], null)));

        InviteOutcome outcome = resolver.redeemInvite(555L, "Masha", "ru", "badtoken").block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.holderTelegramId()).isNull();
        assertThat(outcome.holderReply()).isNull();
        assertThat(outcome.inviteeReply()).isEqualTo("Приглашение недействительно или уже использовано.");
        verify(profile, never()).findById(anyString()); // no inviter lookup when the redeem failed
    }

    // --- Owner-allowlist onboarding gate (issue #627) ---------------------------------------------

    /** A resolver whose onboarding allowlist contains exactly the given ids. */
    private IdentityResolver resolverAllowing(Long... ids) {
        GatewayProperties gated = new GatewayProperties();
        gated.setAllowedTelegramIds(new java.util.HashSet<>(java.util.List.of(ids)));
        return new IdentityResolver(profile, gated);
    }

    @Test
    void newUserNotOnAllowlistIsNotOnboarded() {
        when(profile.findByTelegramId(99L)).thenReturn(Mono.empty());

        UserDto result = resolverAllowing(42L).resolve(99L, "stranger", "ru").block();

        assertThat(result).isNull(); // empty resolve → caller declines, never routes
        verify(profile).findByTelegramId(99L);
        verifyNoMoreInteractions(profile); // no household/user created for an unlisted new id
    }

    @Test
    void newUserOnAllowlistIsOnboarded() {
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserDto created = new UserDto(userId, householdId, "vlad", "ru-RU", 42L, "admin", Instant.now());

        when(profile.findByTelegramId(42L)).thenReturn(Mono.empty());
        when(profile.createHousehold("vlad"))
                .thenReturn(Mono.just(new HouseholdDto(householdId, "vlad", Instant.now())));
        when(profile.createUser(eq(householdId.toString()), eq("vlad"), eq(42L), eq("ru"), eq("admin")))
                .thenReturn(Mono.just(created));

        UserDto result = resolverAllowing(42L).resolve(42L, "vlad", "ru").block();

        assertThat(result).isEqualTo(created);
        verify(profile).createHousehold("vlad");
    }

    @Test
    void existingUserBypassesTheAllowlist() {
        UserDto existing = new UserDto(
                UUID.randomUUID(), UUID.randomUUID(), "member", "ru-RU", 99L, "member", Instant.now());
        when(profile.findByTelegramId(99L)).thenReturn(Mono.just(existing));

        // 99 is NOT on the allowlist, but it's already provisioned → still allowed (the gate only
        // guards new-account creation, not returning members onboarded earlier).
        UserDto result = resolverAllowing(42L).resolve(99L, "member", "ru").block();

        assertThat(result).isEqualTo(existing);
        verify(profile).findByTelegramId(99L);
        verifyNoMoreInteractions(profile);
    }

    @Test
    void invitedNewUserJoinsEvenWhenNotOnAllowlist() {
        UUID inviteeId = UUID.randomUUID();
        UUID inviteeHome = UUID.randomUUID();
        UUID inviterId = UUID.randomUUID();
        UUID family = UUID.randomUUID();
        UserDto invitee = new UserDto(inviteeId, inviteeHome, "Masha", "ru-RU", 555L, "admin", Instant.now());
        UserDto inviter = new UserDto(inviterId, family, "vlad", "ru-RU", 42L, "admin", Instant.now());

        // A brand-new invitee (555) not on the allowlist: the invite token authorizes onboarding, so
        // find-or-create runs ungated and provisions their personal household before the redeem.
        when(profile.findByTelegramId(555L)).thenReturn(Mono.empty());
        when(profile.createHousehold("Masha"))
                .thenReturn(Mono.just(new HouseholdDto(inviteeHome, "Masha", Instant.now())));
        when(profile.createUser(eq(inviteeHome.toString()), eq("Masha"), eq(555L), eq("ru"), eq("admin")))
                .thenReturn(Mono.just(invitee));
        when(profile.redeem("tok", inviteeId.toString())).thenReturn(Mono.just(new HouseholdInviteDto(
                UUID.randomUUID(), "tok", family, inviterId, "daughter", true,
                "accepted", inviteeId, Instant.now(), Instant.now())));
        when(profile.findById(inviterId.toString())).thenReturn(Mono.just(inviter));

        InviteOutcome outcome = resolverAllowing(42L).redeemInvite(555L, "Masha", "ru", "tok").block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.inviteeReply()).contains("daughter");
        assertThat(outcome.holderTelegramId()).isEqualTo(42L);
        verify(profile).createUser(eq(inviteeHome.toString()), eq("Masha"), eq(555L), eq("ru"), eq("admin"));
    }

    @Test
    void strangerCannotMintAnInviteToSlipPastTheGate() {
        when(profile.findByTelegramId(77L)).thenReturn(Mono.empty());

        String reply = resolverAllowing(42L).mintInvite(77L, "stranger", "en", "x", "friend").block();

        assertThat(reply).isEqualTo(IdentityResolver.notAllowedReply("en"));
        verify(profile).findByTelegramId(77L);
        verifyNoMoreInteractions(profile); // no account created, no invite minted
    }
}
