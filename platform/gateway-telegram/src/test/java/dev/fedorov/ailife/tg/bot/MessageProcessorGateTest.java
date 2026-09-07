package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.inbox.InboxWriter;
import dev.fedorov.ailife.tg.config.GatewayProperties;
import dev.fedorov.ailife.tg.identity.IdentityResolver;
import dev.fedorov.ailife.tg.media.MediaServiceClient;
import dev.fedorov.ailife.tg.media.TranscribeClient;
import dev.fedorov.ailife.tg.orchestrator.OrchestratorClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The owner-allowlist gate (issue #627) at the routing seam: when {@link IdentityResolver#resolve}
 * declines a blocked new id (empty), {@link MessageProcessor} must reply "invite-only" and never
 * touch the orchestrator/LLM. Pure unit — mocks the collaborators, no Spring context.
 */
class MessageProcessorGateTest {

    private final IdentityResolver identity = mock(IdentityResolver.class);
    private final OrchestratorClient orchestrator = mock(OrchestratorClient.class);
    private final MediaServiceClient media = mock(MediaServiceClient.class);
    private final TranscribeClient transcribe = mock(TranscribeClient.class);
    private final InboxWriter inbox = mock(InboxWriter.class);
    private final MessageProcessor processor = new MessageProcessor(
            identity, orchestrator, media, transcribe, inbox, new ObjectMapper(), new GatewayProperties());

    @Test
    void blockedNewUserGetsInviteOnlyDeclineAndNeverReachesOrchestrator() {
        // A blocked, unlisted new id resolves to empty (the gate lives in IdentityResolver).
        when(identity.resolve(anyLong(), anyString(), anyString())).thenReturn(Mono.empty());

        var incoming = new MessageProcessor.IncomingMessage(
                999L, "stranger", "ru", "привет", MessageScope.PRIVATE, "1");

        IntentResponse result = processor.process(incoming).block();

        assertThat(result).isNotNull();
        assertThat(result.agent()).isEqualTo("gateway");
        assertThat(result.text()).isEqualTo(IdentityResolver.notAllowedReply("ru"));
        verify(orchestrator, never()).handle(any()); // no routing, no LLM spend
    }
}
