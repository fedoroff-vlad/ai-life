package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.Attachment;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.tg.identity.IdentityResolver;
import dev.fedorov.ailife.tg.media.MediaServiceClient;
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

    public MessageProcessor(IdentityResolver identity,
                            OrchestratorClient orchestrator,
                            MediaServiceClient media) {
        this.identity = identity;
        this.orchestrator = orchestrator;
        this.media = media;
    }

    public Mono<IntentResponse> process(IncomingMessage incoming) {
        return identity.resolve(incoming.telegramUserId(), incoming.displayName(), incoming.languageCode())
                .flatMap(user -> attachmentsFor(user, incoming)
                        .flatMap(attachments -> orchestrator.handle(normalise(user, incoming, attachments))));
    }

    /**
     * A photo message stores its bytes in media-service first; the resulting object id rides on the
     * {@link NormalizedMessage} as an {@link Attachment} (kind=image, storageUri=object id) so a
     * downstream agent can fetch the bytes back. Text-only messages skip this entirely.
     */
    private Mono<List<Attachment>> attachmentsFor(UserDto user, IncomingMessage incoming) {
        IncomingPhoto photo = incoming.photo();
        if (photo == null) {
            return Mono.just(List.of());
        }
        return media.upload(user.householdId(), user.id(), "image", "telegram",
                        photo.filename(), photo.mimeType(), photo.bytes())
                .map(dto -> List.of(
                        new Attachment("image", dto.mimeType(), dto.id().toString(), null)));
    }

    private NormalizedMessage normalise(UserDto user, IncomingMessage incoming, List<Attachment> attachments) {
        return new NormalizedMessage(
                user.id(),
                user.householdId(),
                incoming.scope(),
                incoming.text(),
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
            IncomingPhoto photo) {

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

    /** A downloaded inbound photo: raw bytes plus what to store them as. */
    public record IncomingPhoto(byte[] bytes, String mimeType, String filename) {
    }
}
