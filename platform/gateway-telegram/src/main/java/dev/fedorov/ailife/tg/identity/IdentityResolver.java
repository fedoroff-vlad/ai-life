package dev.fedorov.ailife.tg.identity;

import dev.fedorov.ailife.contracts.profile.HouseholdInviteDto;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.tg.config.GatewayProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Maps a Telegram user to an internal {@link UserDto}, creating their **personal household** + user
 * on first contact (ADR-0001). Identity is 1:N: every new user gets their own isolated single-member
 * household (named after them), of which they are the {@code admin} — never auto-attached to another
 * user's household. profile-service records the self-membership on user creation. Joining a shared
 * (family) household is a separate, owner-gated invite/approve step (slice 4), not part of first
 * contact — so a friend who simply DMs the bot stays fully isolated in their own space.
 */
@Component
public class IdentityResolver {

    private final ProfileClient profile;
    private final GatewayProperties props;

    public IdentityResolver(ProfileClient profile, GatewayProperties props) {
        this.profile = profile;
        this.props = props;
    }

    public Mono<UserDto> resolve(long telegramUserId, String displayName, String languageCode) {
        String locale = languageCode != null && !languageCode.isBlank() ? languageCode : "ru-RU";
        String name = displayName != null && !displayName.isBlank()
                ? displayName : "user-" + telegramUserId;
        return profile.findByTelegramId(telegramUserId)
                .switchIfEmpty(Mono.defer(() ->
                        profile.createHousehold(personalHouseholdName(name))
                                .flatMap(h -> profile.createUser(
                                        h.id().toString(),
                                        name,
                                        telegramUserId,
                                        locale,
                                        "admin"))));
    }

    /**
     * Redeem a {@code /start <token>} family invite (ADR-0001, slice 4b). Resolves the opener's
     * identity first (creating their personal household + user on first contact, exactly like a normal
     * message), then redeems the invite so they join the inviter's family household, and resolves the
     * inviter's Telegram id so the bot layer can ping the holder on the join. An unknown / already-used
     * / revoked token is not an error to the invitee — it yields a graceful {@link InviteOutcome#noJoin}
     * reply, and the opener keeps their own isolated personal space (ADR-0001: no invite → full isolation).
     */
    public Mono<InviteOutcome> redeemInvite(long telegramUserId, String displayName,
                                            String languageCode, String token) {
        boolean ru = languageCode == null || languageCode.startsWith("ru");
        return resolve(telegramUserId, displayName, languageCode)
                .flatMap(invitee -> profile.redeem(token, invitee.id().toString())
                        .flatMap(invite -> joined(invitee, invite, ru))
                        .onErrorResume(WebClientResponseException.class,
                                ex -> Mono.just(InviteOutcome.noJoin(failedReply(ru)))));
    }

    /** Build the join outcome, resolving the inviter's Telegram id for the holder ping. */
    private Mono<InviteOutcome> joined(UserDto invitee, HouseholdInviteDto invite, boolean ru) {
        String inviteeReply = joinedReply(ru, invite.relationship());
        return profile.findById(invite.inviterUserId().toString())
                .map(inviter -> new InviteOutcome(
                        inviteeReply,
                        inviter.telegramUserId(),
                        holderReply(inviter.locale(), invitee.displayName(), invite.relationship())))
                .defaultIfEmpty(InviteOutcome.noJoin(inviteeReply));
    }

    /** Name for a user's personal household. Falls back to the configured default when unnamed. */
    private String personalHouseholdName(String userName) {
        return userName.startsWith("user-")
                ? props.getTelegram().getDefaultHouseholdName()
                : userName;
    }

    private static String joinedReply(boolean ru, String relationship) {
        if (relationship == null || relationship.isBlank()) {
            return ru ? "Готово — вы присоединились к семейному пространству."
                    : "Done — you've joined the family space.";
        }
        return ru ? "Готово — вы присоединились к семейному пространству как " + relationship + "."
                : "Done — you've joined the family space as " + relationship + ".";
    }

    private static String failedReply(boolean ru) {
        return ru ? "Приглашение недействительно или уже использовано."
                : "This invite is invalid or already used.";
    }

    private static String holderReply(String locale, String inviteeName, String relationship) {
        boolean ru = locale == null || locale.startsWith("ru");
        String who = inviteeName != null && !inviteeName.isBlank() ? inviteeName : (ru ? "Новый участник" : "Someone");
        if (relationship == null || relationship.isBlank()) {
            return ru ? who + " присоединился(-ась) к вашему семейному пространству."
                    : who + " joined your family space.";
        }
        return ru ? who + " присоединился(-ась) к вашему семейному пространству как " + relationship + "."
                : who + " joined your family space as " + relationship + ".";
    }
}
