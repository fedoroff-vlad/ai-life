package dev.fedorov.ailife.agents.notes.intent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NoteEditor} (road-test #486, Track H.2 — the notes edit hole): mock
 * {@link LlmClient} + {@link NoteClient} exercise the confirm-before-change gate, the "picked but no change
 * given → ask" branch, the resume-affirmative update (re-read → merge → PUT), the decline, and the no-match
 * branch without an external service. Mirrors {@link NoteDeleterTest}.
 */
class NoteEditorTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final NoteClient notes = mock(NoteClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "notes", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the notes agent.");
    private final Skill skill = new Skill("note-edit", "Fix a note the user names.",
            "0.1.0", "knowledge", List.of(), List.of("en", "ru"), "Pick the note to edit.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final NoteEditor editor = new NoteEditor(llm, manifest, skills, notes, json);

    @Test
    void editByDescriptionConfirmsFirst() {
        UUID household = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        when(notes.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                note(noteId, "Планы на отпуск", "idea", "едем в Сочи"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1,\"newBody\":\"едем в Крым\"}")));

        StepVerifier.create(editor.edit(message(household, "исправь заметку про отпуск: едем в Крым")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Исправить заметку").contains("Планы на отпуск").contains("новый текст");
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("flow").asString()).isEqualTo("note-edit-confirm");
                    assertThat(r.pendingAction().path("noteId").asString()).isEqualTo(noteId.toString());
                    assertThat(r.pendingAction().path("newBody").asString()).isEqualTo("едем в Крым");
                })
                .verifyComplete();

        // Confirm-before-change: nothing was written on the first turn.
        verify(notes, never()).update(any(), any());
    }

    @Test
    void pickedWithoutChangeAsksWhatToChange() {
        UUID household = UUID.randomUUID();
        when(notes.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                note(UUID.randomUUID(), "Планы на отпуск", "idea", "едем в Сочи"))));
        // Note identified, but the user did not say what to change → just the pick.
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1}")));

        StepVerifier.create(editor.edit(message(household, "исправь заметку про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Как исправить").contains("Планы на отпуск");
                    assertThat(r.pendingAction()).isNull();   // no lock — the flow re-asks
                })
                .verifyComplete();

        verify(notes, never()).update(any(), any());
    }

    @Test
    void ambiguousTargetIsClarified() {
        UUID household = UUID.randomUUID();
        when(notes.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                note(UUID.randomUUID(), "Отпуск в горах", "idea", "Алтай"),
                note(UUID.randomUUID(), "Отпуск на море", "idea", "Сочи"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"ambiguous\":[1,2]}")));

        StepVerifier.create(editor.edit(message(household, "исправь заметку про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("несколько").contains("Отпуск в горах").contains("Отпуск на море");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(notes, never()).update(any(), any());
    }

    @Test
    void noMatchAsks() {
        UUID household = UUID.randomUUID();
        when(notes.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                note(UUID.randomUUID(), "Врач", "reference", "терапевт"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{}")));

        StepVerifier.create(editor.edit(message(household, "исправь заметку про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл такую заметку");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(notes, never()).update(any(), any());
    }

    @Test
    void listNotesAreNotEditableCandidates() {
        UUID household = UUID.randomUUID();
        when(notes.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                note(UUID.randomUUID(), "список покупок", "list", "- [ ] молоко"))));

        StepVerifier.create(editor.edit(message(household, "исправь заметку про молоко")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл заметок");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(llm, never()).chat(any());
        verify(notes, never()).update(any(), any());
    }

    @Test
    void resumeAffirmativeUpdatesTheNote() {
        UUID household = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        NoteDto current = note(noteId, household, "Планы на отпуск", "idea", "едем в Сочи");
        when(notes.get(eq(noteId))).thenReturn(Mono.just(current));
        when(notes.update(eq(noteId), any())).thenReturn(Mono.just(current));

        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "note-edit-confirm");
        pending.put("noteId", noteId.toString());
        pending.put("title", "Планы на отпуск");
        pending.put("newBody", "едем в Крым");

        StepVerifier.create(editor.resume(new ResumeRequest(
                        message(household, "да"), pending)))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Исправил заметку").contains("Планы на отпуск");
                    assertThat(r.pendingAction()).isNull();   // lock cleared
                })
                .verifyComplete();

        ArgumentCaptor<WriteNoteRequest> req = ArgumentCaptor.forClass(WriteNoteRequest.class);
        verify(notes).update(eq(noteId), req.capture());
        assertThat(req.getValue().bodyMd()).isEqualTo("едем в Крым");     // change applied
        assertThat(req.getValue().title()).isEqualTo("Планы на отпуск");  // untouched field preserved
        assertThat(req.getValue().householdId()).isEqualTo(household);
    }

    @Test
    void resumeDeclineLeavesIt() {
        UUID noteId = UUID.randomUUID();
        ObjectNode pending = json.createObjectNode();
        pending.put("flow", "note-edit-confirm");
        pending.put("noteId", noteId.toString());
        pending.put("title", "Планы на отпуск");
        pending.put("newBody", "едем в Крым");

        StepVerifier.create(editor.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "нет"), pending)))
                .assertNext(r -> assertThat(r.text()).contains("без изменений"))
                .verifyComplete();

        verify(notes, never()).update(any(), any());
    }

    private static NormalizedMessage message(UUID household, String text) {
        return new NormalizedMessage(UUID.randomUUID(), household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static NoteDto note(UUID id, String title, String type, String bodyMd) {
        return note(id, null, title, type, bodyMd);
    }

    private static NoteDto note(UUID id, UUID household, String title, String type, String bodyMd) {
        return new NoteDto(id, household, null, title, type, List.of(), "user", null, bodyMd, null,
                Instant.now(), Instant.now());
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }
}
