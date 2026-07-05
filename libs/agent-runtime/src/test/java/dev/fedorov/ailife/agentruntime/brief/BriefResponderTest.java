package dev.fedorov.ailife.agentruntime.brief;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.memory.MemoryDto;
import dev.fedorov.ailife.contracts.memory.RecallMemoryHit;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * The reusable {@code brief} read-action (#290, Slice B): a focused sub-question is answered from the
 * agent's persona + its second-brain recall in one FAST-channel synthesis, wrapped as a structured
 * {@link AgentActionResult}. Verifies the wire shape with a mocked {@link LlmClient} + {@link MemoryClient}.
 */
class BriefResponderTest {

    // Mirror Spring Boot's mapper (production wires that one): JSR-310 must be registered or
    // valueToTree of a recalled MemoryDto's Instant field fails and the gather soft-fails to empty.
    private final ObjectMapper json = new ObjectMapper();
    private final AgentManifest manifest = new AgentManifest(
            "finance", "finance agent", "0.1.0", 8093,
            List.of(), List.of(), List.of(), List.of(), "You are the finance agent.");

    @Test
    void answersTheSubQuestionGroundedInRecallOnTheFastChannel() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        LlmClient llm = mock(LlmClient.class);
        MemoryClient memory = mock(MemoryClient.class);
        ArgumentCaptor<LlmChatRequest> cap = ArgumentCaptor.forClass(LlmChatRequest.class);
        when(llm.chat(cap.capture()))
                .thenReturn(Mono.just(new LlmChatResponse("test-model", "You spend most on groceries.", "stop", null)));
        when(memory.recall(any(), any(), any(), any())).thenReturn(Mono.just(List.of(
                new RecallMemoryHit(new MemoryDto(UUID.randomUUID(), household, user, null, "note",
                        "Owner's biggest category is groceries", null, Instant.now()), 0.11))));

        BriefResponder responder = new BriefResponder(new Coordinator(llm, json), memory, manifest, json);

        ObjectNode args = json.createObjectNode();
        args.put("question", "how are my finances relevant to planning my week?");
        AgentActionRequest req = new AgentActionRequest("finance", "brief", household, user, "coordinator", args);

        AgentActionResult res = responder.answer(req).block(Duration.ofSeconds(2));

        assertThat(res).isNotNull();
        assertThat(res.ok()).isTrue();
        assertThat(res.result().get("agent").asText()).isEqualTo("finance");
        assertThat(res.result().get("answer").asText()).isEqualTo("You spend most on groceries.");
        assertThat(res.result().get("llmModel").asText()).isEqualTo("test-model");

        LlmChatRequest sent = cap.getValue();
        assertThat(sent.channel()).isEqualTo(LlmChannel.FAST);   // cheap leg — DEFAULT is the coordinator's final synthesis
        String body = sent.messages().toString();
        assertThat(body).contains("READ-ONLY");                  // the read-only framing is present
        assertThat(body).contains("Owner's biggest category is groceries"); // recall folded into context
        assertThat(body).contains("planning my week");           // the question travelled in the payload
    }

    @Test
    void missingQuestionIsAStructuredError() {
        BriefResponder responder = new BriefResponder(
                new Coordinator(mock(LlmClient.class), json), mock(MemoryClient.class), manifest, json);

        AgentActionRequest req = new AgentActionRequest("finance", "brief", UUID.randomUUID(), null, "coordinator", null);

        AgentActionResult res = responder.answer(req, Map.of()).block(Duration.ofSeconds(2));

        assertThat(res).isNotNull();
        assertThat(res.ok()).isFalse();
        assertThat(res.error()).contains("question");
    }
}
