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
 * Golden test (road-test #485 — "why did you do that" trace, Track G) — proves the <b>real production
 * classifier prompt</b> makes a <b>real model</b> ({@code qwen3:8b} via a running llm-gateway) emit the
 * reserved {@code explain} outcome when the owner asks why/how the previous turn was handled, and only
 * then. Sibling to {@link GoldenMisrouteRepairTest}: both piggyback on the {@link PriorRoute} context —
 * misroute-repair proves a <em>correction</em> re-routes, this proves a <em>why-query</em> classifies to
 * {@code explain} (answered by {@code ExplainResponder}, not dispatched). Asserts <b>structure, not
 * text</b> (roadmap §Risks).
 *
 * <p><b>Opt-in / gated</b> exactly like {@link GoldenRoutingTest} — skipped unless {@code GOLDEN_LLM} is
 * set (CI default = unset). Reuses {@link GoldenRoutingTest#realManifests()} so the prompt is built from
 * the same real per-agent manifests the deployed orchestrator scrapes at startup.
 */
@GoldenLlmTest
class GoldenExplainTraceTest {

    private final MemoryClient memory = mock(MemoryClient.class);
    private final LlmClient llm = GoldenLlm.client();
    private final LlmIntentClassifier classifier = newClassifier();

    GoldenExplainTraceTest() {
        when(memory.recall(any(), any(), any())).thenReturn(Mono.just(List.of()));
    }

    /**
     * BEHAVIOUR — a "why/how did you do that" turn, with the prior route as context, classifies to the
     * reserved {@code explain} outcome (not back to the prior agent, not a fresh domain).
     */
    @Test
    void aWhyQueryAfterAPriorRouteClassifiesToExplain() {
        assertExplains(new PriorRoute("finance", "сколько я потратил на еду"),
                "почему ты так решил?");
        assertExplains(new PriorRoute("tasks", "напомни купить молоко"),
                "как ты это понял?");
        assertExplains(new PriorRoute("calendar", "встреча завтра в 15:00"),
                "а почему ты отправил это туда?");
    }

    /**
     * STRUCTURE — with a prior route present, an ordinary new request must NOT be mistaken for a
     * why-query: it classifies to its real domain, never {@code explain}.
     */
    @Test
    void anOrdinaryMessageDoesNotClassifyToExplain() {
        String agent = classifier.classify(
                        GoldenLlm.message("запиши, что надо оплатить интернет"),
                        new PriorRoute("finance", "сколько я потратил на кофе"))
                .block(Duration.ofSeconds(90));
        assertThat(agent)
                .as("an ordinary request must classify on its own merits, never to 'explain'")
                .isNotEqualTo("explain")
                .isEqualTo("tasks");
    }

    private void assertExplains(PriorRoute prior, String whyQuery) {
        String agent = classifier.classify(GoldenLlm.message(whyQuery), prior)
                .block(Duration.ofSeconds(90));
        assertThat(agent)
                .as("why-query «%s» after a route to '%s' should classify to 'explain' but went to '%s'",
                        whyQuery, prior.agent(), agent)
                .isEqualTo("explain");
    }

    private LlmIntentClassifier newClassifier() {
        AgentRegistryProperties props = new AgentRegistryProperties();
        props.setCatchAllAgent("tasks");
        return new LlmIntentClassifier(llm, memory, new AgentRegistry(GoldenRoutingTest.realManifests()), props);
    }
}
