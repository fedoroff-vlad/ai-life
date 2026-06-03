package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.tg.identity.IdentityResolver;
import dev.fedorov.ailife.tg.orchestrator.OrchestratorClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Pure logic, no Telegram API dependency — easy to unit test.
 * Resolves identity, builds {@link NormalizedMessage}, asks the orchestrator.
 */
@Component
public class MessageProcessor {

    private final IdentityResolver identity;
    private final OrchestratorClient orchestrator;

    public MessageProcessor(IdentityResolver identity, OrchestratorClient orchestrator) {
        this.identity = identity;
        this.orchestrator = orchestrator;
    }

    public Mono<IntentResponse> process(IncomingMessage incoming) {
        return identity.resolve(incoming.telegramUserId(), incoming.displayName(), incoming.languageCode())
                .flatMap(user -> orchestrator.handle(normalise(user, incoming)));
    }

    private NormalizedMessage normalise(UserDto user, IncomingMessage incoming) {
        return new NormalizedMessage(
                user.id(),
                user.householdId(),
                incoming.scope(),
                incoming.text(),
                List.of(),
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
            String messageId) {
    }
}
