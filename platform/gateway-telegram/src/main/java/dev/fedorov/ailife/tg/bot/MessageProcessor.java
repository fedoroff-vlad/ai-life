package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.inbox.InboxWriter;
import dev.fedorov.ailife.tg.config.GatewayProperties;
import dev.fedorov.ailife.tg.identity.IdentityResolver;
import dev.fedorov.ailife.tg.identity.InviteOutcome;
import dev.fedorov.ailife.tg.inbox.InboundEnvelope;
import dev.fedorov.ailife.tg.inbox.InboundReplies;
import dev.fedorov.ailife.tg.media.MediaServiceClient;
import dev.fedorov.ailife.tg.media.TranscribeClient;
import dev.fedorov.ailife.tg.orchestrator.OrchestratorClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Pure logic, no Telegram API dependency — easy to unit test.
 * Resolves identity, uploads any attached media to media-service, builds the
 * {@link NormalizedMessage}, asks the orchestrator.
 */
@Component
public class MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    /** RU-3: reply shown when a voice note can't be understood, asking the owner to re-record. */
    static final String ASK_TO_REPEAT =
            "🎤 Не расслышал — повтори, пожалуйста, голосом ещё раз.";

    private final IdentityResolver identity;
    private final OrchestratorClient orchestrator;
    private final MediaServiceClient media;
    private final TranscribeClient transcribe;
    private final InboxWriter inbox;
    private final ObjectMapper json;
    private final double minConfidence;

    public MessageProcessor(IdentityResolver identity,
                            OrchestratorClient orchestrator,
                            MediaServiceClient media,
                            TranscribeClient transcribe,
                            InboxWriter inbox,
                            ObjectMapper json,
                            GatewayProperties properties) {
        this.identity = identity;
        this.orchestrator = orchestrator;
        this.media = media;
        this.transcribe = transcribe;
        this.inbox = inbox;
        this.json = json;
        this.minConfidence = properties.getStt().getMinConfidence();
    }

    /**
     * Handle a {@code /start <token>} deep-link (ADR-0001 slice 4b): redeem the family invite for the
     * opener (resolving/creating their identity first). Returns the {@link InviteOutcome} the bot layer
     * uses to reply to the invitee and DM the holder — kept off the normal orchestrator route since an
     * invite redemption is identity plumbing, not a routable message.
     */
    public Mono<InviteOutcome> redeemInvite(IncomingMessage incoming, String token) {
        return identity.redeemInvite(
                incoming.telegramUserId(), incoming.displayName(), incoming.languageCode(), token);
    }

    /**
     * Handle the owner-side {@code /invite <name> as <relationship>} command (ADR-0001 slice 4b-ii):
     * mint a family invite into the sender's household and return the deep-link reply for them to
     * forward. Gateway-level identity plumbing, symmetric with the {@code /start} redemption.
     */
    public Mono<String> mintInvite(IncomingMessage incoming, String personLabel, String relationship) {
        return identity.mintInvite(incoming.telegramUserId(), incoming.displayName(),
                incoming.languageCode(), personLabel, relationship);
    }

    public Mono<IntentResponse> process(IncomingMessage incoming) {
        return identity.resolve(incoming.telegramUserId(), incoming.displayName(), incoming.languageCode())
                .flatMap(user -> attachmentsFor(user, incoming)
                        .flatMap(attachments -> route(user, incoming, attachments)))
                // An empty resolve means an unlisted new id was blocked by the owner-allowlist
                // (issue #627): reply "invite-only" and never reach the orchestrator/LLM.
                .switchIfEmpty(Mono.fromSupplier(() -> new IntentResponse(
                        "gateway", IdentityResolver.notAllowedReply(incoming.languageCode()), null)));
    }

    /**
     * Route a message: a captionless voice note is transcribed at the front door first (so a spoken
     * request reaches any agent as ordinary text), then either routed on the transcript or — when the
     * transcript can't be understood — bounced back with an ask-to-repeat reply (#489 RU-3). Anything
     * that already carries text (a typed message, a captioned photo/voice) routes straight through.
     */
    private Mono<IntentResponse> route(UserDto user, IncomingMessage incoming, List<Attachment> attachments) {
        return voiceToTranscribe(incoming, attachments)
                .map(voice -> transcribe.transcribe(voice.storageUri())
                        .flatMap(result -> unintelligible(result)
                                ? Mono.just(askToRepeat())
                                : dispatch(user, incoming, attachments, result.text())))
                .orElseGet(() -> dispatch(user, incoming, attachments, incoming.text()));
    }

    /**
     * Durable dispatch (#633): persist the normalized message to the inbox <b>before</b> calling the
     * orchestrator, so a downstream outage (orchestrator / agent / MCP / llm-gateway) or a gateway
     * restart mid-request can't silently drop it. On success the row is marked {@code PROCESSED} (the
     * redriver never touches it); on a downstream failure the row stays {@code PENDING} for the redriver
     * and the user gets a "queued, will reply once it's back" notice instead of a silent drop.
     *
     * <p>Durability is best-effort: it applies only to real inbound messages (a Telegram {@code update_id}
     * is present) and degrades to plain dispatch when the inbox itself is unavailable — so a DB blip never
     * makes things worse than the pre-#633 behaviour.
     */
    private Mono<IntentResponse> dispatch(UserDto user, IncomingMessage incoming,
                                          List<Attachment> attachments, String text) {
        NormalizedMessage message = normalise(user, incoming, attachments, text);
        Long updateId = incoming.updateId();
        if (inbox == null || updateId == null) {
            return orchestrator.handle(message);
        }
        String dedupKey = "telegram:" + updateId;
        boolean recorded = recordQuietly(dedupKey, incoming, message);
        return orchestrator.handle(message)
                .doOnNext(response -> markProcessedQuietly(dedupKey))
                .onErrorResume(error -> {
                    if (recorded) {
                        log.warn("downstream dispatch failed; message {} queued for redrive", dedupKey, error);
                        return Mono.just(new IntentResponse(
                                "gateway", InboundReplies.queued(incoming.languageCode()), null));
                    }
                    // Not durably recorded (inbox unavailable) — surface as before (bot shows a generic error).
                    return Mono.error(error);
                });
    }

    /** Persist the inbound envelope; never throws — a DB failure just disables durability for this message. */
    private boolean recordQuietly(String dedupKey, IncomingMessage incoming, NormalizedMessage message) {
        try {
            var envelope = new InboundEnvelope(incoming.chatId(), incoming.languageCode(), message);
            return inbox.record(dedupKey, json.writeValueAsString(envelope));
        } catch (RuntimeException e) {
            log.warn("inbox unavailable; dispatching {} without durability", dedupKey, e);
            return false;
        }
    }

    private void markProcessedQuietly(String dedupKey) {
        try {
            inbox.markProcessed(dedupKey);
        } catch (RuntimeException e) {
            log.warn("failed to mark inbox row {} PROCESSED (will be reconciled by redrive)", dedupKey, e);
        }
    }

    /**
     * The voice attachment whose audio must be transcribed before routing — present ONLY for a
     * captionless voice note (a caption or typed text is the payload, so no STT). Not soft-failed:
     * for a voice message the transcript IS the payload, so an STT failure surfaces as an error reply
     * rather than a silent empty route.
     */
    private Optional<Attachment> voiceToTranscribe(IncomingMessage incoming, List<Attachment> attachments) {
        if (incoming.text() != null && !incoming.text().isBlank()) {
            return Optional.empty();
        }
        return attachments.stream()
                .filter(a -> "voice".equals(a.kind()))
                .findFirst();
    }

    /**
     * RU-3 reliability gate: a transcript is unintelligible when it is empty/blank (no speech heard) or
     * its confidence is <b>known and below</b> {@code gateway.stt.min-confidence}. A {@code null}
     * confidence is "unknown", never low, so such a transcript still routes (back-compat).
     */
    private boolean unintelligible(TranscriptResult result) {
        boolean empty = result.text() == null || result.text().isBlank();
        boolean lowConfidence = result.confidence() != null && result.confidence() < minConfidence;
        return empty || lowConfidence;
    }

    private IntentResponse askToRepeat() {
        return new IntentResponse("gateway", ASK_TO_REPEAT, null);
    }

    /**
     * A media message (photo or document) stores its bytes in media-service first; the resulting
     * object id rides on the {@link NormalizedMessage} as an {@link Attachment}
     * (kind=image|file, storageUri=object id) so a downstream agent can fetch the bytes back.
     * Text-only messages skip this entirely.
     */
    private Mono<List<Attachment>> attachmentsFor(UserDto user, IncomingMessage incoming) {
        IncomingMedia m = incoming.media();
        if (m == null) {
            return Mono.just(List.of());
        }
        return media.upload(user.householdId(), user.id(), m.kind(), "telegram",
                        m.filename(), m.mimeType(), m.bytes())
                .map(dto -> List.of(
                        new Attachment(m.kind(), dto.mimeType(), dto.id().toString(), null)));
    }

    private NormalizedMessage normalise(UserDto user, IncomingMessage incoming,
                                        List<Attachment> attachments, String text) {
        return new NormalizedMessage(
                user.id(),
                user.householdId(),
                incoming.scope(),
                text,
                attachments,
                "telegram",
                incoming.messageId(),
                Instant.now());
    }

    public record IncomingMessage(
            long telegramUserId,
            String displayName,
            String languageCode,
            String text,
            MessageScope scope,
            String messageId,
            IncomingMedia media,
            long chatId,
            Long updateId) {

        /** Text-only message — no attached media, no durable-inbox context (invite/callback/test paths). */
        public IncomingMessage(long telegramUserId,
                               String displayName,
                               String languageCode,
                               String text,
                               MessageScope scope,
                               String messageId) {
            this(telegramUserId, displayName, languageCode, text, scope, messageId, null, 0L, null);
        }

        /** Media message without durable-inbox context (test paths). */
        public IncomingMessage(long telegramUserId,
                               String displayName,
                               String languageCode,
                               String text,
                               MessageScope scope,
                               String messageId,
                               IncomingMedia media) {
            this(telegramUserId, displayName, languageCode, text, scope, messageId, media, 0L, null);
        }

        /**
         * Main inbound path (durable): carries the Telegram {@code chatId} (so a deferred redrive can
         * deliver the reply) and {@code updateId} (the inbox dedup key). A {@code null} {@code updateId}
         * disables durability for this message.
         */
        public IncomingMessage(long telegramUserId,
                               String displayName,
                               String languageCode,
                               String text,
                               MessageScope scope,
                               String messageId,
                               IncomingMedia media,
                               long chatId,
                               Long updateId) {
            this.telegramUserId = telegramUserId;
            this.displayName = displayName;
            this.languageCode = languageCode;
            this.text = text;
            this.scope = scope;
            this.messageId = messageId;
            this.media = media;
            this.chatId = chatId;
            this.updateId = updateId;
        }
    }

    /**
     * A downloaded inbound media blob (photo, document or voice note): raw bytes plus what to store
     * them as. {@code kind} is the attachment kind a downstream step branches on — {@code image} for
     * a Telegram photo (receipt flow), {@code file} for a document (e.g. a Money Pro CSV),
     * {@code voice} for a voice note (transcribed to text at the front door).
     */
    public record IncomingMedia(byte[] bytes, String mimeType, String filename, String kind) {
    }
}
