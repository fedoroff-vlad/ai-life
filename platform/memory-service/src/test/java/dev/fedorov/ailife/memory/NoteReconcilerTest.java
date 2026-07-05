package dev.fedorov.ailife.memory;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.memory.capture.NoteReconciler;
import dev.fedorov.ailife.memory.capture.NoteReconciliation;
import dev.fedorov.ailife.memory.capture.ReconcileAction;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-tests {@link NoteReconciler}'s lenient JSON parsing and safe-default behaviour with a mocked
 * {@link LlmClient} — no Docker, no llm-gateway. Mirrors {@code NoteWorthinessExtractorTest}.
 */
class NoteReconcilerTest {

    private final ObjectMapper json = new ObjectMapper();

    private NoteReconciler returning(String content) {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(new LlmChatResponse("mock", content, "stop", null)));
        return new NoteReconciler(llm, json);
    }

    private NoteReconciliation reconcile(String content) {
        return returning(content).reconcile("Мама — аллергия", "аллергия на орехи",
                "Мама — аллергия", "аллергия на орехи и арахис");
    }

    @Test
    void parsesEnrichWithMergedBody() {
        NoteReconciliation r = reconcile(
                "{\"action\":\"enrich\",\"body\":\"аллергия на орехи и арахис\"}");
        assertThat(r.action()).isEqualTo(ReconcileAction.ENRICH);
        assertThat(r.body()).isEqualTo("аллергия на орехи и арахис");
        assertThat(r.rewritesBody()).isTrue();
    }

    @Test
    void parsesSupersede() {
        NoteReconciliation r = reconcile("{\"action\":\"supersede\",\"body\":\"аллергии больше нет\"}");
        assertThat(r.action()).isEqualTo(ReconcileAction.SUPERSEDE);
        assertThat(r.body()).isEqualTo("аллергии больше нет");
        assertThat(r.rewritesBody()).isTrue();
    }

    @Test
    void skipActionRewritesNothing() {
        NoteReconciliation r = reconcile("{\"action\":\"skip\",\"body\":\"\"}");
        assertThat(r.action()).isEqualTo(ReconcileAction.SKIP);
        assertThat(r.rewritesBody()).isFalse();
    }

    @Test
    void stripsMarkdownFencesAndLeadingProse() {
        NoteReconciliation r = reconcile("""
                Sure!
                ```json
                {"action":"enrich","body":"merged"}
                ```""");
        assertThat(r.action()).isEqualTo(ReconcileAction.ENRICH);
        assertThat(r.body()).isEqualTo("merged");
    }

    @Test
    void enrichWithBlankBodyFallsBackToSkip() {
        // An enrich/supersede with no usable body would blank the note — never apply it.
        NoteReconciliation r = reconcile("{\"action\":\"enrich\",\"body\":\"   \"}");
        assertThat(r.action()).isEqualTo(ReconcileAction.SKIP);
        assertThat(r.rewritesBody()).isFalse();
    }

    @Test
    void unknownActionDefaultsToSkip() {
        NoteReconciliation r = reconcile("{\"action\":\"delete\",\"body\":\"nope\"}");
        assertThat(r.action()).isEqualTo(ReconcileAction.SKIP);
    }

    @Test
    void malformedJsonDefaultsToSkip() {
        NoteReconciliation r = reconcile("not json at all");
        assertThat(r.action()).isEqualTo(ReconcileAction.SKIP);
        assertThat(r.rewritesBody()).isFalse();
    }

    @Test
    void nullContentDefaultsToSkip() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(new LlmChatResponse("mock", null, "stop", null)));
        NoteReconciliation r = new NoteReconciler(llm, json)
                .reconcile("t", "b", "t", "b2");
        assertThat(r.action()).isEqualTo(ReconcileAction.SKIP);
    }
}
