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
        assertThat(outcome.inviteeReply()).contains("daughter");
        assertThat(outcome.holderTelegramId()).isEqualTo(42L);
        assertThat(outcome.holderReply()).contains("Masha").contains("daughter");
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
}
