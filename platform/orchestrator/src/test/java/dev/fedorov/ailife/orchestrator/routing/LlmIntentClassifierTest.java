package dev.fedorov.ailife.orchestrator.routing;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.MessageScope;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.llm.LlmRole;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.orchestrator.agent.AgentRegistry;
import dev.fedorov.ailife.orchestrator.agent.AgentRegistryProperties;
import dev.fedorov.ailife.orchestrator.memory.MemoryClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LlmIntentClassifier}'s catch-all routing — mock {@link LlmClient} +
 * {@link MemoryClient} let us inspect the classifier prompt and the routing decision without an
 * external service.
 */
class LlmIntentClassifierTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final MemoryClient memory = mock(MemoryClient.class);

    private final AgentManifest tasksManifest = new AgentManifest(
            "tasks", "GTD task management.", "0.1.0", 8096,
            List.of(), List.of(), List.<Map<String, String>>of(),
            List.of(Map.of("example", "Remind me to call the dentist")),
            "tasks body");
    private final AgentRegistry registry = new AgentRegistry(Map.of("tasks", tasksManifest));

    private static NormalizedMessage msg(String text) {
        return new NormalizedMessage(UUID.randomUUID(), UUID.randomUUID(), MessageScope.PRIVATE,
                text, List.of(), "telegram", "1", Instant.now());
    }

    private static LlmChatResponse reply(String text) {
        return new LlmChatResponse("mock-fast", text, "stop", new LlmUsage(10, 1, 11));
    }

    @Test
    void catchAllAgentReframesPromptAndRoutesUnmatchedActionableToIt() {
        var props = new AgentRegistryProperties();
        props.setCatchAllAgent("tasks");
        var classifier = new LlmIntentClassifier(llm, memory, registry, props);
        when(memory.recall(any(), any(), any())).thenReturn(Mono.just(List.of()));
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("tasks"));
        });

        StepVerifier.create(classifier.classify(msg("купи хлеб по дороге домой")))
                .expectNext("tasks")
                .verifyComplete();

        String prompt = seen.get().messages().stream()
                .filter(m -> m.role() == LlmRole.SYSTEM)
                .map(LlmMessage::content).findFirst().orElse("");
        // echo is narrowed to small talk; tasks is the actionable catch-all.
        assertThat(prompt).contains("reply 'tasks'");
        assertThat(prompt).contains("echo: greetings and small talk only");
        assertThat(prompt).contains("capture-first");
    }

    @Test
    void noCatchAllKeepsEchoAsTheOnlyFallback() {
        var props = new AgentRegistryProperties(); // catchAllAgent = "" (default)
        var classifier = new LlmIntentClassifier(llm, memory, registry, props);
        when(memory.recall(any(), any(), any())).thenReturn(Mono.just(List.of()));
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("echo"));
        });

        StepVerifier.create(classifier.classify(msg("привет")))
                .expectNext("echo")
                .verifyComplete();

        String prompt = seen.get().messages().stream()
                .filter(m -> m.role() == LlmRole.SYSTEM)
                .map(LlmMessage::content).findFirst().orElse("");
        assertThat(prompt).contains("If no specialized agent fits, reply 'echo'");
        assertThat(prompt).doesNotContain("capture-first");
    }

    @Test
    void unknownCatchAllAgentSelfDisablesToEcho() {
        var props = new AgentRegistryProperties();
        props.setCatchAllAgent("ghost"); // not registered → must self-disable
        var classifier = new LlmIntentClassifier(llm, memory, registry, props);
        when(memory.recall(any(), any(), any())).thenReturn(Mono.just(List.of()));
        AtomicReference<LlmChatRequest> seen = new AtomicReference<>();
        when(llm.chat(any(LlmChatRequest.class))).thenAnswer(inv -> {
            seen.set(inv.getArgument(0));
            return Mono.just(reply("echo"));
        });

        StepVerifier.create(classifier.classify(msg("что-то")))
                .expectNext("echo")
                .verifyComplete();

        String prompt = seen.get().messages().stream()
                .filter(m -> m.role() == LlmRole.SYSTEM)
                .map(LlmMessage::content).findFirst().orElse("");
        assertThat(prompt).doesNotContain("ghost");
        assertThat(prompt).contains("If no specialized agent fits, reply 'echo'");
    }
}
