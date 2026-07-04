package dev.fedorov.ailife.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.memory.capture.NoteReconciliation;
import dev.fedorov.ailife.memory.capture.NoteReconciler;
import dev.fedorov.ailife.memory.capture.ReconcileAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 <b>golden test</b> — exercises {@link NoteReconciler} against a <b>real model</b> (local Ollama
 * {@code qwen2.5:7b} via a running llm-gateway), asserting <b>structure, not wording</b>: a near-duplicate
 * that adds a detail enriches, a contradiction supersedes, and an identical restatement is skipped. This is
 * the model behaviour the AC-5 merge/supersede path depends on. Mirrors {@code GoldenNoteWorthinessTest}.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}).
 */
@GoldenLlmTest
class GoldenNoteReconcilerTest {

    private final NoteReconciler reconciler = new NoteReconciler(GoldenLlm.client(), new ObjectMapper());

    @Test
    void aNewDetailEnrichesTheExistingNote() {
        NoteReconciliation r = reconciler.reconcile(
                "Мама — аллергия", "У мамы аллергия на орехи.",
                "Мама — аллергия", "У мамы аллергия на орехи и на арахис.");

        assertThat(r.action()).as("real model reply, is llm-gateway up at %s?", GoldenLlm.gatewayUrl())
                .isNotNull();
        // A new detail must change the note and the merged body must keep the new fact.
        assertThat(r.rewritesBody()).as("a new detail should rewrite the body (got %s)", r).isTrue();
        assertThat(r.body().toLowerCase()).contains("арахис");
    }

    @Test
    void aContradictionSupersedesTheExistingNote() {
        NoteReconciliation r = reconciler.reconcile(
                "Работа", "Решил сменить работу.",
                "Работа", "Передумал менять работу, остаюсь на текущей.");

        // The stale note must be updated, not left as-is.
        assertThat(r.rewritesBody()).as("a contradiction should update the note (got %s)", r).isTrue();
        assertThat(r.action()).isEqualTo(ReconcileAction.SUPERSEDE);
    }

    @Test
    void anIdenticalRestatementIsSkipped() {
        NoteReconciliation r = reconciler.reconcile(
                "Мама — аллергия", "У мамы аллергия на орехи.",
                "Мама — аллергия", "У мамы аллергия на орехи.");

        assertThat(r.action()).as("nothing new should skip (got %s)", r).isEqualTo(ReconcileAction.SKIP);
    }
}
