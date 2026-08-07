package dev.fedorov.ailife.sharing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.common.SharingScope;
import dev.fedorov.ailife.contracts.profile.HouseholdRoutingDto;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reusable sharing confirm-on-ambiguity plumbing (ADR-0002 item 8, DS-N): the pending-action
 * round-trip, reply parsing, and the resume that routes through {@link SharingResolver#confirm} into a
 * per-domain finish. The privacy mechanism itself is {@link SharingResolver}'s (mocked here).
 */
class SharingConfirmTest {

    private final UUID personal = UUID.randomUUID();
    private final UUID family = UUID.randomUUID();
    private final UUID envelope = UUID.randomUUID();

    private final SharingResolver resolver = mock(SharingResolver.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SharingConfirm confirm = new SharingConfirm(resolver, json);

    private final SharingResolution.NeedsConfirm needsConfirm = new SharingResolution.NeedsConfirm(
            new HouseholdRoutingDto(personal, List.of(family)),
            new SharingContext(List.of(), false, "task-unscoped"),
            envelope);

    @Test
    void pendingActionRoundTripsTheNeedsConfirm() {
        ObjectNode stash = json.createObjectNode().put("title", "вынести мусор");
        ObjectNode pending = confirm.pendingAction(needsConfirm, stash);

        assertThat(pending.path("flow").asString()).isEqualTo(SharingConfirm.FLOW);
        assertThat(pending.path("stash").path("title").asString()).isEqualTo("вынести мусор");
        // The routing + context + fallback survive the JSON round-trip so resume can rebuild them.
        assertThat(confirm.needsConfirm(pending)).isEqualTo(needsConfirm);
    }

    @Test
    void resumeOnSharedReplyConfirmsAndFinishes() {
        when(resolver.confirm(needsConfirm, SharingScope.SHARED)).thenReturn(family);
        ObjectNode pending = confirm.pendingAction(needsConfirm, json.createObjectNode().put("title", "мусор"));
        AtomicReference<UUID> finishedInto = new AtomicReference<>();

        SharingConfirm.Finish finish = (household, chosen, stash) -> {
            finishedInto.set(household);
            return Mono.just("Записал в общий список: «" + stash.path("title").asString() + "».");
        };

        StepVerifier.create(confirm.resume(pending, "это общее", finish))
                .assertNext(reply -> {
                    assertThat(reply.text()).contains("общий список").contains("мусор");
                    assertThat(reply.keepPending()).isNull(); // lock cleared
                })
                .verifyComplete();

        verify(resolver).confirm(needsConfirm, SharingScope.SHARED);
        assertThat(finishedInto.get()).isEqualTo(family);
    }

    @Test
    void resumeOnPrivateReplyPicksPersonal() {
        when(resolver.confirm(needsConfirm, SharingScope.PRIVATE)).thenReturn(personal);
        ObjectNode pending = confirm.pendingAction(needsConfirm, json.createObjectNode());

        StepVerifier.create(confirm.resume(pending, "личное", (hh, chosen, stash) -> Mono.just("ok " + hh)))
                .assertNext(reply -> assertThat(reply.text()).isEqualTo("ok " + personal))
                .verifyComplete();
        verify(resolver).confirm(needsConfirm, SharingScope.PRIVATE);
    }

    @Test
    void resumeOnUnclearReplyReasksAndKeepsLock() {
        ObjectNode pending = confirm.pendingAction(needsConfirm, json.createObjectNode());
        AtomicReference<Boolean> finishCalled = new AtomicReference<>(false);

        StepVerifier.create(confirm.resume(pending, "не знаю",
                        (hh, chosen, stash) -> {
                            finishCalled.set(true);
                            return Mono.just("should not happen");
                        }))
                .assertNext(reply -> {
                    assertThat(reply.text()).contains("личное или общее");
                    assertThat(reply.keepPending()).isEqualTo(pending); // lock kept → re-ask
                })
                .verifyComplete();

        assertThat(finishCalled.get()).isFalse();
        verify(resolver, never()).confirm(any(), any());
    }

    @Test
    void parseScopeReadsRussianCues() {
        assertThat(SharingConfirm.parseScope("общее")).contains(SharingScope.SHARED);
        assertThat(SharingConfirm.parseScope("это семейное")).contains(SharingScope.SHARED);
        assertThat(SharingConfirm.parseScope("личное")).contains(SharingScope.PRIVATE);
        assertThat(SharingConfirm.parseScope("персональная задача")).contains(SharingScope.PRIVATE);
        assertThat(SharingConfirm.parseScope("shared")).contains(SharingScope.SHARED);
        // neither cue, or both → unclear (re-ask)
        assertThat(SharingConfirm.parseScope("ага")).isEmpty();
        assertThat(SharingConfirm.parseScope("общее или личное?")).isEmpty();
        assertThat(SharingConfirm.parseScope(null)).isEmpty();
    }
}
