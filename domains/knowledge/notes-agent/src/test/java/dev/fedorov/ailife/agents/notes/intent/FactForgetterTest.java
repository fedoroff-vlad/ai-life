package dev.fedorov.ailife.agents.notes.intent;

import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FactForgetter} (road-test #488, MQ-2 — forget/correct a remembered fact): a mock
 * {@link LlmClient} + {@link MemoryClient} exercise the confirm-before-forget gate, the resume-affirmative
 * forget, the resume-affirmative correct (forget-then-write), the decline, the note-seed exclusion, and the
 * no-match branch without an external service. Mirrors {@code NoteDeleter}/{@code NoteEditor} tests.
 */
class FactForgetterTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final MemoryClient memory = mock(MemoryClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "notes", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the notes agent.");
    private final Skill skill = new Skill("fact-forget", "Forget or correct a remembered fact.",
            "0.1.0", "knowledge", List.of(), List.of("en", "ru"), "Pick the fact to forget.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final FactForgetter forgetter = new FactForgetter(llm, manifest, skills, memory, json);

    @Test
    void forgetByDescriptionConfirmsFirst() {
        UUID household = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        when(memory.listMemories(eq(household), eq(userId), isNull(), anyInt())).thenReturn(Mono.just(List.of(
                fact(memoryId, household, userId, "ambient", "Пользователь курит"),
                fact(UUID.randomUUID(), household, userId, "ambient", "Пользователь пьёт кофе"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1}")));

        StepVerifier.create(forgetter.forget(message(household, userId, "забудь, что я курю")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Забыть").contains("Пользователь курит");
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("flow").asString()).isEqualTo("fact-forget-confirm");
                    assertThat(r.pendingAction().path("memoryId").asString()).isEqualTo(memoryId.toString());
                    assertThat(r.pendingAction().hasNonNull("correction")).isFalse();
                })
                .verifyComplete();

        // Confirm-before-forget: nothing was deleted on the first turn.
        verify(memory, never()).deleteMemory(any());
    }

    @Test
    void correctConfirmsFirstWithCorrection() {
        UUID household = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        when(memory.listMemories(eq(household), eq(userId), isNull(), anyInt())).thenReturn(Mono.just(List.of(
                fact(memoryId, household, userId, "ambient", "Пользователь живёт в Москве"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large",
                        "{\"pick\":1,\"correction\":\"Пользователь живёт в Казани\"}")));

        StepVerifier.create(forgetter.forget(message(household, userId,
                        "это неверно, на самом деле я живу в Казани")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Исправить").contains("Казани");
                    assertThat(r.pendingAction().path("flow").asString()).isEqualTo("fact-forget-confirm");
                    assertThat(r.pendingAction().path("memoryId").asString()).isEqualTo(memoryId.toString());
                    assertThat(r.pendingAction().path("correction").asString())
                            .isEqualTo("Пользователь живёт в Казани");
                })
                .verifyComplete();

        verify(memory, never()).deleteMemory(any());
        verify(memory, never()).remember(any(), any(), any(), any(), any());
    }

    @Test
    void noteSeedFactsAreNotForgettableCandidates() {
        UUID household = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        // Only a note-seed fact exists → excluded (forgetting a note is note-delete's job).
        when(memory.listMemories(eq(household), eq(userId), isNull(), anyInt())).thenReturn(Mono.just(List.of(
                fact(UUID.randomUUID(), household, userId, "note", "Заметка про отпуск"))));

        StepVerifier.create(forgetter.forget(message(household, userId, "забудь про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("ничего про тебя не запомнил");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        // The LLM is never consulted when there are no forgettable candidates.
        verify(llm, never()).chat(any());
        verify(memory, never()).deleteMemory(any());
    }

    @Test
    void ambiguousTargetIsClarified() {
        UUID household = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(memory.listMemories(eq(household), eq(userId), isNull(), anyInt())).thenReturn(Mono.just(List.of(
                fact(UUID.randomUUID(), household, userId, "ambient", "Пользователь любит горы"),
                fact(UUID.randomUUID(), household, userId, "ambient", "Пользователь любит море"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"ambiguous\":[1,2]}")));

        StepVerifier.create(forgetter.forget(message(household, userId, "забудь, что я люблю отдых")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("несколько").contains("горы").contains("море");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(memory, never()).deleteMemory(any());
    }

    @Test
    void noMatchAsks() {
        UUID household = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(memory.listMemories(eq(household), eq(userId), isNull(), anyInt())).thenReturn(Mono.just(List.of(
                fact(UUID.randomUUID(), household, userId, "ambient", "Пользователь любит кофе"))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{}")));

        StepVerifier.create(forgetter.forget(message(household, userId, "забудь, что я вегетарианец")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл такого факта");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(memory, never()).deleteMemory(any());
    }

    @Test
    void resumeAffirmativeForgets() {
        UUID memoryId = UUID.randomUUID();
        when(memory.deleteMemory(eq(memoryId))).thenReturn(Mono.empty());

        StepVerifier.create(forgetter.resume(new ResumeRequest(
                        message(UUID.randomUUID(), UUID.randomUUID(), "да"),
                        pending(memoryId, "Пользователь курит", null))))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Забыл").contains("Пользователь курит");
                    assertThat(r.pendingAction()).isNull();   // lock cleared
                })
                .verifyComplete();

        verify(memory).deleteMemory(eq(memoryId));
        verify(memory, never()).remember(any(), any(), any(), any(), any());
    }

    @Test
    void resumeAffirmativeCorrectsForgetThenWrites() {
        UUID household = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        when(memory.getMemory(eq(memoryId))).thenReturn(Mono.just(
                fact(memoryId, household, userId, "ambient", "Пользователь живёт в Москве")));
        when(memory.deleteMemory(eq(memoryId))).thenReturn(Mono.empty());
        when(memory.remember(eq(household), eq(userId), any(), eq("Пользователь живёт в Казани"), isNull()))
                .thenReturn(Mono.empty());

        StepVerifier.create(forgetter.resume(new ResumeRequest(
                        message(household, userId, "да"),
                        pending(memoryId, "Пользователь живёт в Москве", "Пользователь живёт в Казани"))))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Исправил").contains("Казани");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(memory).getMemory(eq(memoryId));
        verify(memory).deleteMemory(eq(memoryId));
        verify(memory).remember(eq(household), eq(userId), any(), eq("Пользователь живёт в Казани"), isNull());
    }

    @Test
    void resumeDeclineLeavesIt() {
        UUID memoryId = UUID.randomUUID();

        StepVerifier.create(forgetter.resume(new ResumeRequest(
                        message(UUID.randomUUID(), UUID.randomUUID(), "нет"),
                        pending(memoryId, "Пользователь курит", null))))
                .assertNext(r -> assertThat(r.text()).contains("Оставил как есть"))
                .verifyComplete();

        verify(memory, never()).deleteMemory(any());
    }

    private ObjectNode pending(UUID memoryId, String fact, String correction) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", "fact-forget-confirm");
        node.put("memoryId", memoryId.toString());
        node.put("fact", fact);
        if (correction != null) {
            node.put("correction", correction);
        }
        return node;
    }

    private static NormalizedMessage message(UUID household, UUID userId, String text) {
        return new NormalizedMessage(userId, household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static MemoryDto fact(UUID id, UUID household, UUID userId, String source, String text) {
        return new MemoryDto(id, household, userId, null, source, text, null, Instant.now());
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }
}
