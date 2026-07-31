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

    /**
     * Mint a family invite for the owner (ADR-0001, slice 4b-ii). Resolves the sender's identity, then
     * mints a pre-authorized invite into <em>their own</em> household tagged {@code relationship}, and
     * formats a {@code t.me/<bot>?start=<token>} deep-link reply for the owner to forward out-of-band.
     * {@code personLabel} is a human tag for the reply only (the invite is redeemed by whoever opens
     * the link, per slice 4b-i). Handled at the gateway level, symmetric with {@code /start}.
     */
    public Mono<String> mintInvite(long telegramUserId, String displayName, String languageCode,
                                   String personLabel, String relationship) {
        boolean ru = languageCode == null || languageCode.startsWith("ru");
        return resolve(telegramUserId, displayName, languageCode)
                .flatMap(owner -> profile.mintInvite(
                        owner.householdId().toString(), owner.id().toString(), relationship)
                        .map(invite -> mintReply(ru, personLabel, relationship, deepLink(invite.token()))));
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

    /** The `t.me/<bot>?start=<token>` deep-link an invitee opens to redeem the invite (slice 4b-i). */
    private String deepLink(String token) {
        return "https://t.me/" + props.getTelegram().getBotUsername() + "?start=" + token;
    }

    private static String mintReply(boolean ru, String personLabel, String relationship, String link) {
        String rel = relationship != null && !relationship.isBlank() ? relationship : (ru ? "участник" : "member");
        String who = personLabel != null && !personLabel.isBlank() ? personLabel : (ru ? "этого человека" : "them");
        return ru
                ? "Ссылка-приглашение для «" + who + "» как " + rel + ":\n" + link
                        + "\nОтправьте её этому человеку — открыв ссылку, он присоединится к вашему семейному пространству."
                : "Invite link for \"" + who + "\" as " + rel + ":\n" + link
                        + "\nSend it to them — opening the link joins them to your family space.";
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
