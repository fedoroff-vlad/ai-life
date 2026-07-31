package dev.fedorov.ailife.tg.identity;

/**
 * Result of redeeming a {@code /start <token>} family invite (ADR-0001, slice 4b). Carries the reply
 * shown to the invitee and, when the join succeeded, the holder ping to deliver out-of-band. The
 * actual Telegram sends happen in the bot layer (which owns the client) — this record keeps the
 * identity logic free of any Telegram API dependency, and is trivially assertable in tests.
 *
 * @param inviteeReply     message shown to the person who opened the deep-link (join confirmation,
 *                         or a graceful notice when the token is unknown / already used)
 * @param holderTelegramId inviter's Telegram id to notify on a successful join, or {@code null} when
 *                         the join failed or the inviter has no resolvable Telegram id
 * @param holderReply      message to DM the holder on join, or {@code null} when there is no ping
 */
public record InviteOutcome(String inviteeReply, Long holderTelegramId, String holderReply) {

    /** A join that did not happen (unknown / already-used / revoked token): reply only, no ping. */
    static InviteOutcome noJoin(String inviteeReply) {
        return new InviteOutcome(inviteeReply, null, null);
    }
}
