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
 *
 * <p><b>Onboarding is owner-allowlisted (issue #627).</b> First-contact account creation is gated by
 * {@link GatewayProperties#isOnboardingAllowed(long)} so a stranger who finds the bot can't burn the
 * owner's LLM budget: an unlisted new id is declined (empty resolve → {@link #notAllowedReply}), while
 * an empty allowlist keeps the old allow-all behaviour and a valid family invite still onboards.
 */
@Component
public class IdentityResolver {

    private final ProfileClient profile;
    private final GatewayProperties props;

    public IdentityResolver(ProfileClient profile, GatewayProperties props) {
        this.profile = profile;
        this.props = props;
    }

    /**
     * Resolve identity for a normal inbound message, <b>owner-gated on first contact</b> (issue #627):
     * a returning user is reused as-is, but a brand-new id is provisioned only when
     * {@link GatewayProperties#isOnboardingAllowed(long)} permits it (an empty allowlist = allow all,
     * back-compat). A blocked new id yields an <b>empty</b> {@link Mono} — the caller turns that into a
     * polite "invite-only" decline and never routes to the orchestrator, so a stranger can't burn the
     * owner's LLM budget. The invite path ({@link #redeemInvite}) uses the ungated
     * {@link #findOrCreate} instead, since the token is its own authorization.
     */
    public Mono<UserDto> resolve(long telegramUserId, String displayName, String languageCode) {
        return profile.findByTelegramId(telegramUserId)
                .switchIfEmpty(Mono.defer(() -> props.isOnboardingAllowed(telegramUserId)
                        ? createPersonal(telegramUserId, displayName, languageCode)
                        : Mono.empty()));
    }

    /**
     * Find-or-create with <b>no allowlist gate</b> — used only where creation is already authorized by
     * something other than the allowlist (a family-invite redemption). Everyday message routing goes
     * through the gated {@link #resolve} instead.
     */
    private Mono<UserDto> findOrCreate(long telegramUserId, String displayName, String languageCode) {
        return profile.findByTelegramId(telegramUserId)
                .switchIfEmpty(Mono.defer(() -> createPersonal(telegramUserId, displayName, languageCode)));
    }

    /** Provision a new user + their own personal household (ADR-0001). */
    private Mono<UserDto> createPersonal(long telegramUserId, String displayName, String languageCode) {
        String locale = languageCode != null && !languageCode.isBlank() ? languageCode : "ru-RU";
        String name = displayName != null && !displayName.isBlank()
                ? displayName : "user-" + telegramUserId;
        return profile.createHousehold(personalHouseholdName(name))
                .flatMap(h -> profile.createUser(
                        h.id().toString(),
                        name,
                        telegramUserId,
                        locale,
                        "admin"));
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
        // Ungated find-or-create: a valid invite token authorizes onboarding regardless of the
        // owner-allowlist (issue #627), so a redeemed invitee joins even when their id isn't listed.
        return findOrCreate(telegramUserId, displayName, languageCode)
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
        // Gated: a stranger's `/invite` must not create an account either — otherwise minting would be
        // a trivial way to slip past the owner-allowlist (issue #627). A blocked new id gets the same
        // invite-only decline as a normal message.
        return resolve(telegramUserId, displayName, languageCode)
                .flatMap(owner -> profile.mintInvite(
                        owner.householdId().toString(), owner.id().toString(), relationship)
                        .map(invite -> mintReply(ru, personLabel, relationship, deepLink(invite.token()))))
                .switchIfEmpty(Mono.just(notAllowedReply(languageCode)));
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

    /**
     * The join reply is a short **orientation** for the new member (#490 FO-1), not a bare
     * confirmation: it says what already works with no setup (capture / recall / briefing), how to set
     * personal preferences conversationally, and that personal items stay private. Everything below is
     * on by sensible defaults, so a member who sets nothing hits no dead-ends (ADR-0001/0002).
     */
    private static String joinedReply(boolean ru, String relationship) {
        boolean hasRel = relationship != null && !relationship.isBlank();
        if (ru) {
            String head = hasRel
                    ? "🎉 Готово — вы в семейном пространстве как " + relationship + "."
                    : "🎉 Готово — вы в семейном пространстве.";
            return head + "\n\n"
                    + "Можно начинать сразу, без настройки:\n"
                    + "• пишите мне заметки, задачи, траты, документы — я запомню и найду;\n"
                    + "• напишите «брифинг» — соберу утреннюю сводку.\n\n"
                    + "Чтобы подстроить под себя, просто скажите в чате, например:\n"
                    + "• «я живу в Казани»\n"
                    + "• «я встаю в 7, брифинг в 7:15»\n\n"
                    + "Личные записи видите только вы; общее с семьёй остаётся общим.";
        }
        String head = hasRel
                ? "🎉 Done — you're in the family space as " + relationship + "."
                : "🎉 Done — you're in the family space.";
        return head + "\n\n"
                + "You can start right away, no setup needed:\n"
                + "• send me notes, tasks, expenses, documents — I'll remember and find them;\n"
                + "• type \"briefing\" for a morning digest.\n\n"
                + "To tailor things to you, just say it in chat, e.g.:\n"
                + "• \"I live in Kazan\"\n"
                + "• \"I get up at 7, briefing at 7:15\"\n\n"
                + "Your personal items stay private; what's shared with the family stays shared.";
    }

    private static String failedReply(boolean ru) {
        return ru ? "Приглашение недействительно или уже использовано."
                : "This invite is invalid or already used.";
    }

    /**
     * Reply shown to an unlisted new Telegram id when onboarding is owner-gated (issue #627). Kept
     * generic on purpose — it neither confirms an account was created nor leaks who the owner is.
     */
    public static String notAllowedReply(String languageCode) {
        boolean ru = languageCode == null || languageCode.startsWith("ru");
        return ru
                ? "Это приватный бот. Доступ по приглашению — попросите владельца прислать ссылку-приглашение."
                : "This is a private bot. Access is invite-only — ask the owner to send you an invite link.";
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
