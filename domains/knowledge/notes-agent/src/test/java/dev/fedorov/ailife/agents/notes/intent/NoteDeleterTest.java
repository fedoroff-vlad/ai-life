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
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
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
 * Unit tests for {@link NoteDeleter} (road-test #486, Track H.2 — the notes delete hole): mock
 * {@link LlmClient} + {@link NoteClient} exercise the confirm-before-delete gate, the resume-affirmative
 * delete, the decline, and the no-match branch without an external service. Mirrors finance's
 * {@code TransactionDeleterTest} + tasks' {@code TaskDeleterTest}.
 */
class NoteDeleterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final NoteClient notes = mock(NoteClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "notes", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the notes agent.");
    private final Skill skill = new Skill("note-delete", "Delete a note the user names.",
            "0.1.0", "knowledge", List.of(), List.of("en", "ru"), "Pick the note to delete.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final NoteDeleter deleter = new NoteDeleter(llm, manifest, skills, notes, json);

    @Test
    void deleteByDescriptionConfirmsFirst() {
        UUID household = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        when(notes.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                note(noteId, "Планы на отпуск", "idea", "хочу в горы летом"),
                note(UUID.randomUUID(), "Врач", "reference", "записаться к терапевту"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1}")));

        StepVerifier.create(deleter.delete(message(household, "удали заметку про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Удалить заметку").contains("Планы на отпуск");
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("flow").asString()).isEqualTo("note-delete-confirm");
                    assertThat(r.pendingAction().path("noteId").asString()).isEqualTo(noteId.toString());
                })
                .verifyComplete();

        // Confirm-before-delete: nothing was deleted on the first turn.
        verify(notes, never()).delete(any());
    }

    @Test
    void listNotesAreNotDeletableCandidates() {
        UUID household = UUID.randomUUID();
        // Only a type=list note exists → it's excluded from the candidate pool (lists are LI-a's job).
        when(notes.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                note(UUID.randomUUID(), "список покупок", "list", "- [ ] молоко"))));

        StepVerifier.create(deleter.delete(message(household, "удали заметку про молоко")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл заметок");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        // The LLM is never even consulted when there are no deletable candidates.
        verify(llm, never()).chat(any());
        verify(notes, never()).delete(any());
    }

    @Test
    void ambiguousTargetIsClarified() {
        UUID household = UUID.randomUUID();
        when(notes.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                note(UUID.randomUUID(), "Отпуск в горах", "idea", "Алтай"),
                note(UUID.randomUUID(), "Отпуск на море", "idea", "Сочи"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"ambiguous\":[1,2]}")));

        StepVerifier.create(deleter.delete(message(household, "удали заметку про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("несколько").contains("Отпуск в горах").contains("Отпуск на море");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(notes, never()).delete(any());
    }

    @Test
    void noMatchAsks() {
        UUID household = UUID.randomUUID();
        when(notes.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                note(UUID.randomUUID(), "Врач", "reference", "терапевт"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{}")));

        StepVerifier.create(deleter.delete(message(household, "удали заметку про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл такую заметку");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(notes, never()).delete(any());
    }

    @Test
    void resumeAffirmativeDeletes() {
        UUID noteId = UUID.randomUUID();
        when(notes.delete(eq(noteId))).thenReturn(Mono.empty());

        StepVerifier.create(deleter.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "да"), pending(noteId, "Планы на отпуск"))))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Удалил заметку").contains("Планы на отпуск");
                    assertThat(r.pendingAction()).isNull();   // lock cleared
                })
                .verifyComplete();

        verify(notes).delete(eq(noteId));
    }

    @Test
    void resumeDeclineLeavesIt() {
        UUID noteId = UUID.randomUUID();

        StepVerifier.create(deleter.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "нет"), pending(noteId, "Планы на отпуск"))))
                .assertNext(r -> assertThat(r.text()).contains("без изменений"))
                .verifyComplete();

        verify(notes, never()).delete(any());
    }

    private ObjectNode pending(UUID noteId, String title) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", "note-delete-confirm");
        node.put("noteId", noteId.toString());
        node.put("title", title);
        return node;
    }

    private static NormalizedMessage message(UUID household, String text) {
        return new NormalizedMessage(UUID.randomUUID(), household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static NoteDto note(UUID id, String title, String type, String bodyMd) {
        return new NoteDto(id, null, null, title, type, List.of(), "user", null, bodyMd, null,
                Instant.now(), Instant.now());
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }
}
