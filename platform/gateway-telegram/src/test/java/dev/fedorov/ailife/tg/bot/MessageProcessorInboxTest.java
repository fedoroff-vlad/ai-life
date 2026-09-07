package dev.fedorov.ailife.tg.bot;

import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.profile.UserDto;
import dev.fedorov.ailife.inbox.InboxWriter;
import dev.fedorov.ailife.tg.config.GatewayProperties;
import dev.fedorov.ailife.tg.identity.IdentityResolver;
import dev.fedorov.ailife.tg.inbox.InboundReplies;
import dev.fedorov.ailife.tg.media.MediaServiceClient;
import dev.fedorov.ailife.tg.media.TranscribeClient;
import dev.fedorov.ailife.tg.orchestrator.OrchestratorClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Durable inbound inbox (#633) at the routing seam — proves persist-before-process semantics without a
 * DB (mocked {@link InboxWriter}). The end-to-end redrive-delivers-once proof lives in
 * {@code libs/inbox}'s {@code InboxIntegrationTest}; this covers the gateway-side WHEN/THEN behaviour.
 */
class MessageProcessorInboxTest {

    private final IdentityResolver identity = mock(IdentityResolver.class);
    private final OrchestratorClient orchestrator = mock(OrchestratorClient.class);
    private final MediaServiceClient media = mock(MediaServiceClient.class);
    private final TranscribeClient transcribe = mock(TranscribeClient.class);
    private final InboxWriter inbox = mock(InboxWriter.class);
    private final MessageProcessor processor = new MessageProcessor(
            identity, orchestrator, media, transcribe, inbox, new ObjectMapper(), new GatewayProperties());

    private void resolvesToUser() {
        when(identity.resolve(anyLong(), anyString(), anyString())).thenReturn(Mono.just(new UserDto(
                UUID.randomUUID(), UUID.randomUUID(), "vlad", "ru", 99L, "admin", Instant.now())));
    }

    /** update_id 5 present → durable path; dedup key is "telegram:5". */
    private MessageProcessor.IncomingMessage durableIncoming() {
        return new MessageProcessor.IncomingMessage(
                99L, "vlad", "ru", "привет", MessageScope.PRIVATE, "7", null, 4242L, 5L);
    }

    // Scenario: downstream is down for a durably-recorded message → user gets the "queued" notice
    // (not a silent drop), the row stays PENDING (never marked PROCESSED) for the redriver.
    @Test
    void downstreamOutageQueuesInsteadOfDropping() {
        resolvesToUser();
        when(inbox.record(anyString(), anyString())).thenReturn(true);
        when(orchestrator.handle(any())).thenReturn(Mono.error(new RuntimeException("orchestrator down")));

        IntentResponse result = processor.process(durableIncoming()).block();

        assertThat(result).isNotNull();
        assertThat(result.agent()).isEqualTo("gateway");
        assertThat(result.text()).isEqualTo(InboundReplies.queued("ru"));
        verify(inbox).record(anyString(), anyString());
        verify(inbox, never()).markProcessed(anyString());
    }

    // Scenario: a successful in-request dispatch marks the row PROCESSED so the redriver never re-sends it.
    @Test
    void successMarksProcessed() {
        resolvesToUser();
        when(inbox.record(anyString(), anyString())).thenReturn(true);
        when(orchestrator.handle(any()))
                .thenReturn(Mono.just(new IntentResponse("finance", "записал", "mock-large")));

        IntentResponse result = processor.process(durableIncoming()).block();

        assertThat(result).isNotNull();
        assertThat(result.text()).isEqualTo("записал");
        verify(inbox).markProcessed("telegram:5");
    }

    // Scenario: the inbox itself is unavailable (DB blip) → degrade to the pre-#633 behaviour, i.e. a
    // downstream failure surfaces as an error (the bot's generic notice), never worse than before.
    @Test
    void inboxUnavailableDegradesToPlainDispatch() {
        resolvesToUser();
        when(inbox.record(anyString(), anyString())).thenThrow(new RuntimeException("db down"));
        when(orchestrator.handle(any())).thenReturn(Mono.error(new RuntimeException("orchestrator down")));

        assertThatThrownBy(() -> processor.process(durableIncoming()).block())
                .hasMessageContaining("orchestrator down");
        verify(inbox, never()).markProcessed(anyString());
    }
}
