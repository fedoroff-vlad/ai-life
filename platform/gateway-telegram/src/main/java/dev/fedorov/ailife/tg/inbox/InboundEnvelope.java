package dev.fedorov.ailife.tg.inbox;

import dev.fedorov.ailife.contracts.agent.NormalizedMessage;

/**
 * What the gateway persists to {@code bus.inbox} for a durable inbound message (#633): the normalized
 * message to re-dispatch plus the Telegram reply target, so a deferred redrive can both re-run
 * {@code orchestrator.handle(...)} and deliver the answer to the right chat.
 *
 * <p>Serialised to the inbox row's JSON payload. Media is already durable in media-service (the message
 * carries only the object ids as attachments), so a redrive re-dispatches without re-uploading anything.
 */
public record InboundEnvelope(
        long chatId,
        String languageCode,
        NormalizedMessage message) {
}
