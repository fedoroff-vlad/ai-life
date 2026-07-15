package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.tg.identity.IdentityResolver;
import dev.fedorov.ailife.tg.media.MediaServiceClient;
import dev.fedorov.ailife.tg.media.TranscribeClient;
import dev.fedorov.ailife.tg.orchestrator.OrchestratorClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Pure logic, no Telegram API dependency — easy to unit test.
 * Resolves identity, uploads any attached media to media-service, builds the
 * {@link NormalizedMessage}, asks the orchestrator.
 */
@Component
public class MessageProcessor {

    private final IdentityResolver identity;
    private final OrchestratorClient orchestrator;
    private final MediaServiceClient media;
    private final TranscribeClient transcribe;

    public MessageProcessor(IdentityResolver identity,
                            OrchestratorClient orchestrator,
                            MediaServiceClient media,
                            TranscribeClient transcribe) {
        this.identity = identity;
        this.orchestrator = orchestrator;
        this.media = media;
        this.transcribe = transcribe;
    }

    public Mono<IntentResponse> process(IncomingMessage incoming) {
        return identity.resolve(incoming.telegramUserId(), incoming.displayName(), incoming.languageCode())
                .flatMap(user -> attachmentsFor(user, incoming)
                        .flatMap(attachments -> transcribeIfVoice(incoming, attachments)
                                .map(text -> normalise(user, incoming, attachments, text))
                                .defaultIfEmpty(normalise(user, incoming, attachments, incoming.text()))
                                .flatMap(orchestrator::handle)));
    }

    /**
     * Front-door speech-to-text: a voice note carries no caption, so its uploaded audio is
     * transcribed to text (via mcp-media-processing) before routing — the orchestrator then handles
     * it exactly like a typed message. Emits the transcript ONLY for a captionless voice note; empty
     * otherwise, so the caller falls back to the original text (a captionless photo keeps a null text).
     * Not soft-failed: for a voice message the transcript IS the payload, so an STT failure surfaces
     * as an error reply rather than a silent empty route.
     */
    private Mono<String> transcribeIfVoice(IncomingMessage incoming, List<Attachment> attachments) {
        if (incoming.text() != null && !incoming.text().isBlank()) {
            return Mono.empty();
        }
        return attachments.stream()
                .filter(a -> "voice".equals(a.kind()))
                .findFirst()
                .map(a -> transcribe.transcribe(a.storageUri()))
                .orElseGet(Mono::empty);
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
            IncomingMedia media) {

        /** Text-only message — no attached media. */
        public IncomingMessage(long telegramUserId,
                               String displayName,
                               String languageCode,
                               String text,
                               MessageScope scope,
                               String messageId) {
            this(telegramUserId, displayName, languageCode, text, scope, messageId, null);
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
