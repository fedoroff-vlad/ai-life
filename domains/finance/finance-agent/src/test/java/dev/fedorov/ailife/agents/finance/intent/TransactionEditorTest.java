package dev.fedorov.ailife.agents.finance.intent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.TransactionClient;
import dev.fedorov.ailife.agents.finance.read.SpendingReads;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.agent.ResumeRequest;
import dev.fedorov.ailife.contracts.finance.FinTransactionDto;
import dev.fedorov.ailife.contracts.finance.UpdateTransactionInput;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Unit tests for {@link TransactionEditor} (road-test #486, Track H.2 — the finance edit hole): mock
 * {@link LlmClient} + {@link SpendingReads} + {@link TransactionClient} exercise the confirm-before-change
 * gate, the ask-when-no-change re-ask ({@code missing}), the resume-affirmative update (sign preserved from
 * the existing row), the decline, and the ambiguous / no-match branches. Mirrors {@link TransactionDeleterTest}
 * + tasks' {@code TaskEditorTest}.
 */
class TransactionEditorTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final SpendingReads spendingReads = mock(SpendingReads.class);
    private final TransactionClient transactions = mock(TransactionClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "finance", "test", "0.0.1", 0,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            "You are the finance agent.");
    private final Skill skill = new Skill("transaction-edit", "Edit a logged transaction the user names.",
            "0.1.0", "finance", List.of(), List.of("en", "ru"), "Pick the transaction and the change.");
    private final SkillRegistry skills = new SkillRegistry(List.of(skill));

    private final TransactionEditor editor =
            new TransactionEditor(llm, manifest, skills, spendingReads, transactions, json);

    @Test
    void amountEditConfirmsFirst() {
        UUID household = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        when(spendingReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(transactions.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                tx(txId, "-3.50", "EUR", "coffee", Instant.parse("2026-06-02T08:00:00Z")),
                tx(UUID.randomUUID(), "-12.00", "EUR", "такси", Instant.parse("2026-06-01T08:00:00Z")))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1,\"newAmount\":5.5}")));

        StepVerifier.create(editor.edit(message(household, "исправь сумму траты про кофе на 5.5")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Изменить трату").contains("coffee").contains("5.5");
                    assertThat(r.pendingAction()).isNotNull();
                    assertThat(r.pendingAction().path("flow").asString()).isEqualTo("transaction-edit-confirm");
                    assertThat(r.pendingAction().path("transactionId").asString()).isEqualTo(txId.toString());
                    assertThat(r.pendingAction().path("newAmount").asString()).isEqualTo("5.5");
                })
                .verifyComplete();

        // Confirm-before-change: nothing was written on the first turn.
        verify(transactions, never()).update(any(), any());
    }

    @Test
    void pickedButNoChangeAsksWhat() {
        UUID household = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        when(spendingReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(transactions.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                tx(txId, "-3.50", "EUR", "coffee", Instant.parse("2026-06-02T08:00:00Z")))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"pick\":1}")));

        StepVerifier.create(editor.edit(message(household, "поправь трату про кофе")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Что изменить").contains("coffee");
                    assertThat(r.pendingAction()).isNull();   // no lock — just a question
                })
                .verifyComplete();

        verify(transactions, never()).update(any(), any());
    }

    @Test
    void ambiguousTargetIsClarified() {
        UUID household = UUID.randomUUID();
        when(spendingReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(transactions.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                tx(UUID.randomUUID(), "-12.00", "EUR", "такси Yandex", Instant.parse("2026-06-02T08:00:00Z")),
                tx(UUID.randomUUID(), "-9.00", "EUR", "такси Uber", Instant.parse("2026-06-01T08:00:00Z")))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{\"ambiguous\":[1,2]}")));

        StepVerifier.create(editor.edit(message(household, "исправь трату про такси")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("несколько").contains("такси Yandex").contains("такси Uber");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(transactions, never()).update(any(), any());
    }

    @Test
    void noMatchAsks() {
        UUID household = UUID.randomUUID();
        when(spendingReads.households(eq(household), any(), eq(true)))
                .thenReturn(Mono.just(List.of(household)));
        when(transactions.list(eq(household), anyInt())).thenReturn(Mono.just(List.of(
                tx(UUID.randomUUID(), "-3.50", "EUR", "coffee", Instant.parse("2026-06-01T08:00:00Z")))));
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(reply("mock-large", "{}")));

        StepVerifier.create(editor.edit(message(household, "исправь трату про отпуск")))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Не нашёл такую трату");
                    assertThat(r.pendingAction()).isNull();
                })
                .verifyComplete();

        verify(transactions, never()).update(any(), any());
    }

    @Test
    void resumeAffirmativeUpdatesKeepingSign() {
        UUID txId = UUID.randomUUID();
        // The existing row is an expense (negative) — the new magnitude must be re-signed negative.
        when(transactions.fetch(eq(txId)))
                .thenReturn(Mono.just(Optional.of(tx(txId, "-3.50", "EUR", "coffee", Instant.now()))));
        when(transactions.update(eq(txId), any(UpdateTransactionInput.class)))
                .thenReturn(Mono.just(tx(txId, "-5.50", "EUR", "coffee", Instant.now())));

        ObjectNode pending = pending(txId, "«coffee» на 3.50 EUR");
        pending.put("newAmount", 5.5);

        StepVerifier.create(editor.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "да"), pending)))
                .assertNext(r -> {
                    assertThat(r.text()).contains("Изменил трату").contains("coffee");
                    assertThat(r.pendingAction()).isNull();   // lock cleared
                })
                .verifyComplete();

        ArgumentCaptor<UpdateTransactionInput> captor = ArgumentCaptor.forClass(UpdateTransactionInput.class);
        verify(transactions).update(eq(txId), captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("-5.5"); // sign preserved
        assertThat(captor.getValue().id()).isEqualTo(txId);
    }

    @Test
    void resumeDeclineLeavesIt() {
        UUID txId = UUID.randomUUID();
        ObjectNode pending = pending(txId, "«coffee» на 3.50 EUR");
        pending.put("newAmount", 5.5);

        StepVerifier.create(editor.resume(new ResumeRequest(
                        message(UUID.randomUUID(), "нет"), pending)))
                .assertNext(r -> assertThat(r.text()).contains("без изменений"))
                .verifyComplete();

        verify(transactions, never()).update(any(), any());
    }

    private ObjectNode pending(UUID txId, String label) {
        ObjectNode node = json.createObjectNode();
        node.put("flow", "transaction-edit-confirm");
        node.put("transactionId", txId.toString());
        node.put("label", label);
        return node;
    }

    private static NormalizedMessage message(UUID household, String text) {
        return new NormalizedMessage(UUID.randomUUID(), household, MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static FinTransactionDto tx(UUID id, String amount, String currency, String note, Instant ts) {
        return new FinTransactionDto(id, null, null, null, null,
                new BigDecimal(amount), currency, ts, note, "manual", null, ts);
    }

    private static LlmChatResponse reply(String model, String text) {
        return new LlmChatResponse(model, text, "stop", new LlmUsage(10, 5, 15));
    }
}
