package dev.fedorov.ailife.memory;

import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.WriteRelationRequest;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.memory.capture.ExtractedRelation;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import dev.fedorov.ailife.memory.capture.NoteCandidate;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
import dev.fedorov.ailife.memory.capture.RelationExtractor;
import dev.fedorov.ailife.memory.config.MemoryServiceProperties;
import dev.fedorov.ailife.memory.http.ProfileClient;
import dev.fedorov.ailife.memory.service.CaptureService;
import dev.fedorov.ailife.memory.service.MemoryService;
import dev.fedorov.ailife.memory.service.NoteService;
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
 * Unit-tests the relation- and note-capture wiring of {@link CaptureService} with mocked
 * collaborators — no Docker, no LLM, no profile-service. {@link FactExtractor}
 * returns no facts so the focus stays on the side-effect halves.
 */
class CaptureServiceTest {

    private final FactExtractor facts = mock(FactExtractor.class);
    private final RelationExtractor relationExtractor = mock(RelationExtractor.class);
    private final NoteWorthinessExtractor noteExtractor = mock(NoteWorthinessExtractor.class);
    private final MemoryService memories = mock(MemoryService.class);
    private final RelationService relations = mock(RelationService.class);
    private final NoteService notes = mock(NoteService.class);
    private final ProfileClient profile = mock(ProfileClient.class);
    private final MemoryServiceProperties props = new MemoryServiceProperties();

    private final CaptureService service = new CaptureService(
            facts, relationExtractor, noteExtractor, memories, relations, notes, profile, props);

    private final UUID household = UUID.randomUUID();
    private final UUID speaker = UUID.randomUUID();

    private CaptureRequest req(UUID userId) {
        when(facts.extract(any())).thenReturn(List.of());
        return new CaptureRequest(household, userId, null, "some message");
    }

    /** Turn on the ambient note path (off by default) and stub the note extractor's output. */
    private void enableNotes(NoteCandidate... candidates) {
        props.getAmbientCapture().setEnabled(true);
        when(relationExtractor.extract(any())).thenReturn(List.of());
        when(noteExtractor.extract(any())).thenReturn(List.of(candidates));
    }

    private static NoteCandidate explicit(String title, String type, String body, String subject) {
        return new NoteCandidate(title, type, body, subject, "important", true);
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

    // --- ambient note capture (AC-2) -----------------------------------------------------------------

    @Test
    void notesDisabledByDefault_nothingWritten() {
        when(relationExtractor.extract(any())).thenReturn(List.of());
        when(noteExtractor.extract(any()))
                .thenReturn(List.of(explicit("Мама — аллергия", "person", "аллергия на орехи", "Мама")));

        service.capture(req(speaker));   // props.ambientCapture.enabled defaults to false

        verify(notes, never()).create(any());
        verify(noteExtractor, never()).extract(any());
    }

    @Test
    void explicitSelfFixation_writesOwnerScopedNote() {
        enableNotes(explicit("Сменить работу", "goal", "решил сменить работу", "self"));

        service.capture(req(speaker));

        ArgumentCaptor<WriteNoteRequest> captor = ArgumentCaptor.forClass(WriteNoteRequest.class);
        verify(notes).create(captor.capture());
        WriteNoteRequest w = captor.getValue();
        assertThat(w.householdId()).isEqualTo(household);
        assertThat(w.ownerId()).isEqualTo(speaker);
        assertThat(w.personId()).isNull();
        assertThat(w.type()).isEqualTo("goal");
        assertThat(w.source()).isEqualTo("user");
        assertThat(w.bodyMd()).isEqualTo("решил сменить работу");   // no wiki-link for self
    }

    @Test
    void explicitNamedFixation_resolvesPersonAndAppendsLink() {
        UUID mama = UUID.randomUUID();
        when(profile.resolvePersonId(household, "Мама")).thenReturn(mama);
        enableNotes(explicit("Мама — аллергия", "person", "аллергия на орехи", "Мама"));

        service.capture(req(speaker));

        ArgumentCaptor<WriteNoteRequest> captor = ArgumentCaptor.forClass(WriteNoteRequest.class);
        verify(notes).create(captor.capture());
        WriteNoteRequest w = captor.getValue();
        assertThat(w.personId()).isEqualTo(mama);
        assertThat(w.bodyMd()).contains("[[Мама]]");
    }

    @Test
    void unresolvedName_stillWritesWithDanglingLink() {
        when(profile.resolvePersonId(eq(household), any())).thenReturn(null);
        enableNotes(explicit("Стас — совет", "person", "советует книгу", "Стас"));

        service.capture(req(speaker));

        ArgumentCaptor<WriteNoteRequest> captor = ArgumentCaptor.forClass(WriteNoteRequest.class);
        verify(notes).create(captor.capture());
        WriteNoteRequest w = captor.getValue();
        assertThat(w.personId()).isNull();
        assertThat(w.bodyMd()).contains("[[Стас]]");   // note still saved, link dangles
    }

    @Test
    void importantInferred_notWrittenYet() {
        props.getAmbientCapture().setEnabled(true);
        when(relationExtractor.extract(any())).thenReturn(List.of());
        when(noteExtractor.extract(any()))
                .thenReturn(List.of(new NoteCandidate("t", "fact", "b", "self", "important", false)));

        service.capture(req(speaker));

        verify(notes, never()).create(any());   // approval path is AC-4
    }

    @Test
    void blankTitleCandidate_skipped() {
        enableNotes(explicit("  ", "fact", "some body", "self"));

        service.capture(req(speaker));

        verify(notes, never()).create(any());
    }

    @Test
    void noteWriteFailureNeverBreaksCapture() {
        enableNotes(explicit("t", "goal", "b", "self"));
        when(notes.create(any())).thenThrow(new RuntimeException("db blip"));

        assertThatCode(() -> service.capture(req(speaker))).doesNotThrowAnyException();
    }
}
