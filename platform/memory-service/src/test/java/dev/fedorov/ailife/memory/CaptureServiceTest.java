package dev.fedorov.ailife.memory;

import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.WriteRelationRequest;
import dev.fedorov.ailife.memory.capture.ExtractedRelation;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import dev.fedorov.ailife.memory.capture.RelationExtractor;
import dev.fedorov.ailife.memory.http.ProfileClient;
import dev.fedorov.ailife.memory.service.CaptureService;
import dev.fedorov.ailife.memory.service.MemoryService;
import dev.fedorov.ailife.memory.service.RelationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests the relation-capture wiring of {@link CaptureService} with mocked
 * collaborators — no Docker, no LLM, no profile-service. {@link FactExtractor}
 * returns no facts so the focus stays on the graph half.
 */
class CaptureServiceTest {

    private final FactExtractor facts = mock(FactExtractor.class);
    private final RelationExtractor relationExtractor = mock(RelationExtractor.class);
    private final MemoryService memories = mock(MemoryService.class);
    private final RelationService relations = mock(RelationService.class);
    private final ProfileClient profile = mock(ProfileClient.class);

    private final CaptureService service =
            new CaptureService(facts, relationExtractor, memories, relations, profile);

    private final UUID household = UUID.randomUUID();
    private final UUID speaker = UUID.randomUUID();

    private CaptureRequest req(UUID userId) {
        when(facts.extract(any())).thenReturn(List.of());
        return new CaptureRequest(household, userId, null, "some message");
    }

    @Test
    void selfRelationAnchorsOnTheSpeakerUser() {
        when(relationExtractor.extract(any()))
                .thenReturn(List.of(new ExtractedRelation("self", "likes", "jazz")));

        service.capture(req(speaker));

        ArgumentCaptor<WriteRelationRequest> captor = ArgumentCaptor.forClass(WriteRelationRequest.class);
        verify(relations).write(captor.capture());
        WriteRelationRequest w = captor.getValue();
        assertThat(w.householdId()).isEqualTo(household);
        assertThat(w.subjectType()).isEqualTo("user");
        assertThat(w.subjectId()).isEqualTo(speaker);
        assertThat(w.edge()).isEqualTo("likes");
        assertThat(w.objectType()).isEqualTo("label");
        assertThat(w.objectId()).isNull();
        assertThat(w.objectLabel()).isEqualTo("jazz");
        assertThat(w.source()).isEqualTo("chat-capture");
    }

    @Test
    void namedSubjectResolvesToAPerson() {
        UUID maria = UUID.randomUUID();
        when(profile.resolvePersonId(household, "Maria")).thenReturn(maria);
        when(relationExtractor.extract(any()))
                .thenReturn(List.of(new ExtractedRelation("Maria", "works_at", "Google")));

        service.capture(req(speaker));

        ArgumentCaptor<WriteRelationRequest> captor = ArgumentCaptor.forClass(WriteRelationRequest.class);
        verify(relations).write(captor.capture());
        WriteRelationRequest w = captor.getValue();
        assertThat(w.subjectType()).isEqualTo("person");
        assertThat(w.subjectId()).isEqualTo(maria);
        assertThat(w.edge()).isEqualTo("works_at");
        assertThat(w.objectLabel()).isEqualTo("Google");
    }

    @Test
    void selfRelationWithoutSpeakerIsSkipped() {
        when(relationExtractor.extract(any()))
                .thenReturn(List.of(new ExtractedRelation("self", "likes", "jazz")));

        service.capture(req(null));

        verify(relations, never()).write(any());
    }

    @Test
    void unresolvedSubjectIsSkipped() {
        when(profile.resolvePersonId(eq(household), any())).thenReturn(null);
        when(relationExtractor.extract(any()))
                .thenReturn(List.of(new ExtractedRelation("Stranger", "likes", "books")));

        service.capture(req(speaker));

        verify(relations, never()).write(any());
    }

    @Test
    void relationWriteFailureNeverBreaksCapture() {
        when(relationExtractor.extract(any()))
                .thenReturn(List.of(new ExtractedRelation("self", "likes", "jazz")));
        when(relations.write(any())).thenThrow(new RuntimeException("db blip"));

        assertThatCode(() -> service.capture(req(speaker))).doesNotThrowAnyException();
    }
}
