package dev.fedorov.ailife.agents.coordinator.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agents.coordinator.config.CoordinatorAgentProperties;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199 pattern) for the coordinator's synthesis against a <b>real model</b>
 * (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting <b>structure, not text</b>: given
 * a fixed set of recalled second-brain facts, the real model must synthesize a non-trivial answer that is
 * <i>grounded</i> in those facts — never one invented from thin air. This is the #290 output-half check on
 * a weak model; the same code scales up on a strong provider with no change.
 *
 * <p><b>Opt-in / gated.</b> Skipped unless {@code GOLDEN_LLM} is set (CI default = unset). To run it:
 * <pre>
 *   scripts/golden.sh -pl domains/assistant/coordinator-agent -Dtest=GoldenCoordinatorSynthesisTest
 * </pre>
 *
 * <p>{@link MemoryClient} is mocked to supply the recalled facts; the real {@link Coordinator} runs the
 * one synthesis hop over the real AGENT.md body. We assert the reply grounds in the supplied facts
 * (a distinctive proper noun survives), never wording.
 */
@GoldenLlmTest
class GoldenCoordinatorSynthesisTest {

    // Mirror Spring Boot's auto-configured mapper (the one production wires into the Coordinator):
    // JSR-310 must be registered or valueToTree of a MemoryDto's Instant field fails and the recall
    // gather step soft-fails to empty — leaving the synthesis with no context to ground in.
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final LlmClient llm = GoldenLlm.client();
    private final Coordinator coordinator = new Coordinator(llm, json);
    private final MemoryClient memory = mock(MemoryClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "coordinator", "cross-cutting coordinator", "0.1.0", 8119,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenCoordinatorSynthesisTest.class.getClassLoader()));
    // Empty specialist roster → the live-brief gather leg short-circuits to empty (no hub/planner call);
    // max-rounds=1 pins the loop to one-shot so this golden asserts a single memory-grounded synthesis.
    private final CoordinatorAgentProperties props = onePassProps();
    private final SpecialistBriefs specialistBriefs =
            new SpecialistBriefs(mock(OrchestratorInvokeClient.class), llm, props, json);
    private final SufficiencyAssessor assessor = new SufficiencyAssessor(llm, json);
    private final MultiDomainCoordinator flow =
            new MultiDomainCoordinator(coordinator, memory, specialistBriefs, assessor, props, manifest, json);

    private static CoordinatorAgentProperties onePassProps() {
        CoordinatorAgentProperties p = new CoordinatorAgentProperties();
        p.setMaxRounds(1);
        return p;
    }

    /** A distinctive proper noun the model should preserve if it actually grounded in the recall. */
    private static final String PROJECT = "Northwind";

    @Test
    void synthesisIsGroundedInTheRecalledFacts() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(memory.recall(any(), any(), any(), any())).thenReturn(Mono.just(List.of(
                new RecallMemoryHit(fact(household, user,
                        "Owner is preparing the " + PROJECT + " proposal, due next month"), 0.10),
                new RecallMemoryHit(fact(household, user,
                        "Owner keeps Friday afternoons for deep, focused work"), 0.20))));

        IntentResponse resp = flow.handle(GoldenLlm.message(household,
                "помоги свести всё вместе и предложи, как спланировать неделю")).block(Duration.ofSeconds(150));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        String text = resp.text();
        assertThat(text).as("empty synthesis").isNotBlank();
        assertThat(text.length()).as("synthesis is implausibly short: %s", text).isGreaterThan(60);
        assertThat(text)
                .as("synthesis did not ground in the recalled facts (proper noun «%s» absent):\n%s", PROJECT, text)
                .contains(PROJECT);
    }

    private static MemoryDto fact(UUID household, UUID user, String text) {
        return new MemoryDto(UUID.randomUUID(), household, user, null, "note", text, null, Instant.now());
    }
}
