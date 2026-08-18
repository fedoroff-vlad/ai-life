package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.orchestrator.agent.AgentRegistry;
import dev.fedorov.ailife.orchestrator.agent.AgentRegistryProperties;
import dev.fedorov.ailife.orchestrator.memory.MemoryClient;
import dev.fedorov.ailife.orchestrator.routing.LlmIntentClassifier.PriorRoute;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden test (road-test #484 — misroute-repair) — proves that the <b>real production classifier
 * prompt</b> makes a <b>real model</b> ({@code qwen3:8b} via a running llm-gateway) honour a correction:
 * given the previous (wrong) route as {@link PriorRoute} context, a one-turn correction ("не то, это
 * задача") re-routes to the corrected agent instead of re-picking the prior one — the behaviour the
 * unit tests can only stub. Asserts <b>structure, not text</b> (roadmap §Risks).
 *
 * <p><b>Opt-in / gated</b> exactly like {@link GoldenRoutingTest} — skipped unless {@code GOLDEN_LLM} is
 * set (CI default = unset). Run instructions there apply verbatim (point a llm-gateway at a local Ollama,
 * then {@code GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=… mvn -pl platform/orchestrator
 * -Dtest=GoldenMisrouteRepairTest test}). Reuses {@link GoldenRoutingTest#realManifests()} so the prompt
 * is built from the same real per-agent manifests the deployed orchestrator scrapes at startup.
 */
@GoldenLlmTest
class GoldenMisrouteRepairTest {

    private final MemoryClient memory = mock(MemoryClient.class);
    private final LlmClient llm = GoldenLlm.client();
    private final LlmIntentClassifier classifier = newClassifier();

    GoldenMisrouteRepairTest() {
        when(memory.recall(any(), any(), any())).thenReturn(Mono.just(List.of()));
    }

    /**
     * BEHAVIOUR — a correction re-routes. Each case: the prior turn landed on the wrong agent
     * ({@code prior}); this turn is the owner correcting it; the corrected message must reach
     * {@code expected}, not fall back to {@code prior}.
     */
    @Test
    void aCorrectionReRoutesToTheCorrectedAgent() {
        // Misrouted a task to the calendar → "это не встреча, а задача".
        assertCorrects(new PriorRoute("calendar", "напомни позвонить врачу"),
                "не то, это не встреча — просто напомни, задача", "tasks");
        // Misrouted a spend to tasks → "я про трату денег".
        assertCorrects(new PriorRoute("tasks", "потратил 1000 на такси"),
                "не то, это про расход денег, а не задача", "finance");
        // Misrouted a recipe ask to the nutritionist log → "я хотел рецепт".
        assertCorrects(new PriorRoute("nutritionist", "курица с рисом"),
                "нет, я не про учёт еды — дай рецепт", "chef");
    }

    /**
     * STRUCTURE — an <em>unrelated</em> next message must not be hijacked by the prior-route context:
     * with a stale prior route present, a clean new request still classifies on its own merits.
     */
    @Test
    void anUnrelatedMessageIgnoresThePriorRoute() {
        String agent = classifier.classify(
                        GoldenLlm.message("Добавь встречу завтра в 15:00 про планирование"),
                        new PriorRoute("finance", "сколько я потратил на кофе"))
                .block(Duration.ofSeconds(90));
        assertThat(agent)
                .as("an unrelated new request must classify on its own, not stay on the prior route")
                .isEqualTo("calendar");
    }

    private void assertCorrects(PriorRoute prior, String correction, String expected) {
        String agent = classifier.classify(GoldenLlm.message(correction), prior)
                .block(Duration.ofSeconds(90));
        assertThat(agent)
                .as("correction «%s» after a route to '%s' should re-route to '%s' but went to '%s'",
                        correction, prior.agent(), expected, agent)
                .isEqualTo(expected);
    }

    private LlmIntentClassifier newClassifier() {
        AgentRegistryProperties props = new AgentRegistryProperties();
        props.setCatchAllAgent("tasks");
        return new LlmIntentClassifier(llm, memory, new AgentRegistry(GoldenRoutingTest.realManifests()), props);
    }
}
