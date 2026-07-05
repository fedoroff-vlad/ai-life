package dev.fedorov.ailife.memory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.memory.CaptureRequest;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.contracts.memory.WriteRelationRequest;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.memory.capture.ExtractedRelation;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import dev.fedorov.ailife.memory.capture.NoteCandidate;
import dev.fedorov.ailife.memory.capture.NoteReconciler;
import dev.fedorov.ailife.memory.capture.NoteReconciliation;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
import dev.fedorov.ailife.memory.capture.ReconcileAction;
import dev.fedorov.ailife.memory.capture.RelationExtractor;
import dev.fedorov.ailife.memory.config.MemoryServiceProperties;
import dev.fedorov.ailife.memory.http.ConversationStateClient;
import dev.fedorov.ailife.memory.http.NotifierClient;
import dev.fedorov.ailife.memory.http.ProfileClient;
import dev.fedorov.ailife.memory.service.CaptureService;
import dev.fedorov.ailife.memory.service.MemoryService;
import dev.fedorov.ailife.memory.service.NoteService;
import dev.fedorov.ailife.memory.service.RelationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
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
    private final NoteReconciler reconciler = mock(NoteReconciler.class);
    private final MemoryService memories = mock(MemoryService.class);
    private final RelationService relations = mock(RelationService.class);
    private final NoteService notes = mock(NoteService.class);
    private final ProfileClient profile = mock(ProfileClient.class);
    private final ConversationStateClient conversation = mock(ConversationStateClient.class);
    private final NotifierClient notifier = mock(NotifierClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MemoryServiceProperties props = new MemoryServiceProperties();

    private final CaptureService service = new CaptureService(
            facts, relationExtractor, noteExtractor, reconciler, memories, relations, notes, profile,
            conversation, notifier, json, props);

    private final UUID household = UUID.randomUUID();
    private final UUID speaker = UUID.randomUUID();

    private CaptureRequest req(UUID userId) {
        when(facts.extract(any())).thenReturn(List.of());
        return new CaptureRequest(household, userId, null, "some message");
    }

    private CaptureRequest req(UUID userId, String channel) {
        when(facts.extract(any())).thenReturn(List.of());
        return new CaptureRequest(household, userId, null, "some message", channel);
    }

    /** Turn on the ambient note path (off by default) and stub the note extractor's output. */
    private void enableNotes(NoteCandidate... candidates) {
        props.getAmbientCapture().setEnabled(true);
        when(relationExtractor.extract(any())).thenReturn(List.of());
        when(noteExtractor.extract(any())).thenReturn(List.of(candidates));
        when(memories.recall(any())).thenReturn(List.of());   // AC-3: no dedup neighbours by default
    }

    private static NoteCandidate explicit(String title, String type, String body, String subject) {
        return new NoteCandidate(title, type, body, subject, "important", true);
    }

    /** An important-but-inferred candidate (no fixation cue) → the AC-4 approval path. */
    private static NoteCandidate inferred(String title, String type, String body, String subject) {
        return new NoteCandidate(title, type, body, subject, "important", false);
    }

    private void enableInferred(NoteCandidate... candidates) {
        props.getAmbientCapture().setEnabled(true);
        when(relationExtractor.extract(any())).thenReturn(List.of());
        when(noteExtractor.extract(any())).thenReturn(List.of(candidates));
    }

    /** A non-note recall hit (or a note hit without a refId) at the given distance. */
    private static RecallMemoryHit hit(String source, double distance) {
        MemoryDto m = new MemoryDto(UUID.randomUUID(), null, null, null, source, "existing", null, null);
        return new RecallMemoryHit(m, distance);
    }

    /** A {@code source=note} recall hit carrying the note's {@code refId} back-pointer (SB-2). */
    private RecallMemoryHit noteHit(UUID refId, double distance) {
        var meta = json.createObjectNode().put("refId", refId.toString());
        MemoryDto m = new MemoryDto(UUID.randomUUID(), null, null, null, "note", "existing", meta, null);
        return new RecallMemoryHit(m, distance);
    }

    private static NoteDto existingNote(UUID id, String title, String body) {
        return new NoteDto(id, UUID.randomUUID(), null, title, "fact", null, "user", null, body, null, null, null);
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

    // --- AC-4 approval of important-but-inferred candidates -------------------------------------------

    @Test
    void inferredWithChannel_locksAndAsksApproval() {
        UUID mama = UUID.randomUUID();
        when(profile.resolvePersonId(household, "Мама")).thenReturn(mama);
        enableInferred(inferred("Мама — аллергия", "person", "аллергия на орехи", "Мама"));
        when(conversation.lock(any(), any(), any(), any(), any())).thenReturn(true);

        service.capture(req(speaker, "telegram"));

        ArgumentCaptor<JsonNode> pending = ArgumentCaptor.forClass(JsonNode.class);
        verify(conversation).lock(eq(household), eq(speaker), eq("telegram"), eq("notes"), pending.capture());
        JsonNode p = pending.getValue();
        assertThat(p.path("flow").asText()).isEqualTo("ambient-approve");
        assertThat(p.path("note").path("source").asText()).isEqualTo("ambient");
        assertThat(p.path("note").path("personId").asText()).isEqualTo(mama.toString());
        assertThat(p.path("note").path("bodyMd").asText()).contains("[[Мама]]");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notifier).notify(eq(speaker), text.capture());
        assertThat(text.getValue()).contains("записать");

        verify(notes, never()).create(any());   // not written until the owner approves
    }

    @Test
    void inferredWithoutChannel_notAsked() {
        enableInferred(inferred("t", "fact", "b", "self"));

        service.capture(req(speaker));   // no channel → no conversation to ask on

        verify(conversation, never()).lock(any(), any(), any(), any(), any());
        verify(notifier, never()).notify(any(), any());
        verify(notes, never()).create(any());
    }

    @Test
    void inferredWithoutUserId_notAsked() {
        enableInferred(inferred("t", "fact", "b", "self"));

        service.capture(req(null, "telegram"));   // channel but no speaker to notify

        verify(conversation, never()).lock(any(), any(), any(), any(), any());
        verify(notifier, never()).notify(any(), any());
    }

    @Test
    void lockFails_doesNotAsk() {
        enableInferred(inferred("t", "fact", "b", "self"));
        when(conversation.lock(any(), any(), any(), any(), any())).thenReturn(false);

        service.capture(req(speaker, "telegram"));

        verify(notifier, never()).notify(any(), any());   // never ask if we can't remember the question
    }

    @Test
    void approvalFailureNeverBreaksCapture() {
        enableInferred(inferred("t", "fact", "b", "self"));
        when(conversation.lock(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("blip"));

        assertThatCode(() -> service.capture(req(speaker, "telegram"))).doesNotThrowAnyException();
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

    // --- AC-3 dedup + AC-5 reconcile on a near-duplicate ---------------------------------------------

    @Test
    void nearDuplicateWithNewDetail_enrichesExistingNote() {
        UUID existingId = UUID.randomUUID();
        enableNotes(explicit("Мама — аллергия", "person", "аллергия на орехи и арахис", "Мама"));
        when(memories.recall(any())).thenReturn(List.of(noteHit(existingId, 0.05)));   // < 0.15 threshold
        when(notes.get(existingId))
                .thenReturn(Optional.of(existingNote(existingId, "Мама — аллергия", "аллергия на орехи")));
        when(reconciler.reconcile(any(), any(), any(), any()))
                .thenReturn(new NoteReconciliation(ReconcileAction.ENRICH, "аллергия на орехи и арахис"));

        service.capture(req(speaker));

        ArgumentCaptor<WriteNoteRequest> captor = ArgumentCaptor.forClass(WriteNoteRequest.class);
        verify(notes).update(eq(existingId), captor.capture());
        assertThat(captor.getValue().bodyMd()).isEqualTo("аллергия на орехи и арахис");
        verify(notes, never()).create(any());   // enriched in place, not duplicated
    }

    @Test
    void nearDuplicateNothingNew_leavesExistingNoteUntouched() {
        UUID existingId = UUID.randomUUID();
        enableNotes(explicit("Мама — аллергия", "person", "аллергия на орехи", "Мама"));
        when(memories.recall(any())).thenReturn(List.of(noteHit(existingId, 0.05)));
        when(notes.get(existingId))
                .thenReturn(Optional.of(existingNote(existingId, "Мама — аллергия", "аллергия на орехи")));
        when(reconciler.reconcile(any(), any(), any(), any())).thenReturn(NoteReconciliation.skip());

        service.capture(req(speaker));

        verify(notes, never()).update(any(), any());
        verify(notes, never()).create(any());
    }

    @Test
    void nearDuplicateVanished_createsTheNewNote() {
        UUID existingId = UUID.randomUUID();
        enableNotes(explicit("Мама — аллергия", "person", "аллергия на орехи", "Мама"));
        when(memories.recall(any())).thenReturn(List.of(noteHit(existingId, 0.05)));
        when(notes.get(existingId)).thenReturn(Optional.empty());   // raced away before we read it

        service.capture(req(speaker));

        verify(notes).create(any());
        verify(reconciler, never()).reconcile(any(), any(), any(), any());
    }

    @Test
    void distantNote_written() {
        enableNotes(explicit("Сменить работу", "goal", "решил сменить работу", "self"));
        when(memories.recall(any())).thenReturn(List.of(hit("note", 0.6)));   // > 0.15 threshold

        service.capture(req(speaker));

        verify(notes).create(any());
    }

    @Test
    void nonNoteNeighbourIgnoredForDedup_written() {
        enableNotes(explicit("Сменить работу", "goal", "решил сменить работу", "self"));
        // a very-near neighbour, but it's a chat-capture memory, not a note — must not block the write
        when(memories.recall(any())).thenReturn(List.of(hit("chat-capture", 0.01)));

        service.capture(req(speaker));

        verify(notes).create(any());
    }

    @Test
    void dedupRecallFailure_writesAnyway() {
        enableNotes(explicit("Сменить работу", "goal", "решил сменить работу", "self"));
        when(memories.recall(any())).thenThrow(new RuntimeException("recall blip"));

        service.capture(req(speaker));

        verify(notes).create(any());   // fail-open: a dedup lookup blip never drops the note
    }
}
