package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.orchestrator.agent.AgentRegistry;
import dev.fedorov.ailife.orchestrator.agent.AgentRegistryProperties;
import dev.fedorov.ailife.orchestrator.memory.MemoryClient;
import dev.fedorov.ailife.orchestrator.routing.LlmIntentClassifier.Undoable;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden test (road-test #486, Track H — CRUD/undo) — proves that the <b>real production classifier
 * prompt</b> makes a <b>real model</b> ({@code qwen3:8b} via a running llm-gateway) emit the reserved
 * {@code undo} outcome when THIS turn asks to undo the last action <em>and</em> an {@link Undoable} is
 * offered, and does <b>not</b> emit it for an unrelated message. Asserts <b>structure, not text</b>
 * (roadmap §Risks) — the behaviour the unit tests can only stub.
 *
 * <p><b>Opt-in / gated</b> exactly like {@link GoldenRoutingTest} — skipped unless {@code GOLDEN_LLM} is
 * set (CI default = unset). Run instructions there apply verbatim (point a llm-gateway at a local Ollama,
 * then {@code GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=… mvn -pl platform/orchestrator
 * -Dtest=GoldenUndoTest test}). Reuses {@link GoldenRoutingTest#realManifests()} so the prompt is built
 * from the same real per-agent manifests the deployed orchestrator scrapes at startup.
 */
@GoldenLlmTest
class GoldenUndoTest {

    private final MemoryClient memory = mock(MemoryClient.class);
    private final LlmClient llm = GoldenLlm.client();
    private final LlmIntentClassifier classifier = newClassifier();

    GoldenUndoTest() {
        when(memory.recall(any(), any(), any())).thenReturn(Mono.just(List.of()));
    }

    /** BEHAVIOUR — with an undoable action present, an "undo" phrasing resolves to the reserved outcome. */
    @Test
    void anUndoPhrasingResolvesToTheUndoOutcome() {
        Undoable undoable = new Undoable("задачу «купить молоко»");
        assertUndo("отмени последнее", undoable);
        assertUndo("нет, верни как было", undoable);
        assertUndo("убери то, что ты только что добавил", undoable);
    }

    /**
     * STRUCTURE — an <em>unrelated</em> next message must not be hijacked by the undoable context: with an
     * undoable present, a clean new request still classifies on its own merits, not as an undo.
     */
    @Test
    void anUnrelatedMessageIgnoresTheUndoable() {
        String agent = classifier.classify(
                        GoldenLlm.message("Добавь встречу завтра в 15:00 про планирование"),
                        null, new Undoable("трату 1500 ₽"))
                .block(Duration.ofSeconds(90));
        assertThat(agent)
                .as("an unrelated new request must classify on its own, not become an undo")
                .isEqualTo("calendar");
    }

    private void assertUndo(String text, Undoable undoable) {
        String outcome = classifier.classify(GoldenLlm.message(text), null, undoable)
                .block(Duration.ofSeconds(90));
        assertThat(outcome)
                .as("«%s» with an undoable present should resolve to 'undo' but was '%s'", text, outcome)
                .isEqualTo(IntentRouter.UNDO);
    }

    private LlmIntentClassifier newClassifier() {
        AgentRegistryProperties props = new AgentRegistryProperties();
        props.setCatchAllAgent("tasks");
        return new LlmIntentClassifier(llm, memory, new AgentRegistry(GoldenRoutingTest.realManifests()), props);
    }
}
