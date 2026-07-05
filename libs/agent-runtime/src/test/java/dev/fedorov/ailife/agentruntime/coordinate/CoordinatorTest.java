package dev.fedorov.ailife.agentruntime.coordinate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmRole;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoordinatorTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void gathersInParallelThenSynthesizesFromMergedContext() {
        LlmClient llm = mock(LlmClient.class);
        ArgumentCaptor<LlmChatRequest> cap = ArgumentCaptor.forClass(LlmChatRequest.class);
        when(llm.chat(cap.capture()))
                .thenReturn(Mono.just(new LlmChatResponse("test-model", "GIFT IDEAS", "stop", null)));

        Coordinator coord = new Coordinator(llm, json);

        JsonNode payload = json.createObjectNode().put("personId", "maria");
        ArrayNode memories = json.createArrayNode().add("likes memes");
        ObjectNode budget = json.createObjectNode().put("max", 10000);

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("memory", Mono.just(memories));
        gather.put("budget", Mono.just(budget));

        CoordinationResult res = coord.coordinate(
                List.of("AGENT BODY", "SKILL BODY"), payload, gather, LlmChannel.DEFAULT)
                .block(Duration.ofSeconds(2));

        assertThat(res).isNotNull();
        assertThat(res.text()).isEqualTo("GIFT IDEAS");
        assertThat(res.llmModel()).isEqualTo("test-model");
        assertThat(res.gathered().get("memory")).isNotNull();
        assertThat(res.gathered().get("budget").get("max").asInt()).isEqualTo(10000);

        LlmChatRequest req = cap.getValue();
        assertThat(req.channel()).isEqualTo(LlmChannel.DEFAULT);
        assertThat(req.messages()).hasSize(3); // two system prompts + one user
        assertThat(req.messages().get(0).role()).isEqualTo(LlmRole.SYSTEM);
        assertThat(req.messages().get(1).content()).isEqualTo("SKILL BODY");
        String userContent = req.messages().get(2).content();
        assertThat(userContent)
                .contains("\"max\":10000")
                .contains("likes memes")
                .contains("maria");
    }

    @Test
    void softFailsABrokenGatherStepAndStillSynthesizes() {
        LlmClient llm = mock(LlmClient.class);
        ArgumentCaptor<LlmChatRequest> cap = ArgumentCaptor.forClass(LlmChatRequest.class);
        when(llm.chat(cap.capture()))
                .thenReturn(Mono.just(new LlmChatResponse("m", "OK", "stop", null)));

        Coordinator coord = new Coordinator(llm, json);

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("budget", Mono.just(json.createObjectNode().put("max", 50)));
        gather.put("weather", Mono.error(new RuntimeException("upstream down")));
        gather.put("empty", Mono.just(json.createArrayNode())); // empty → omitted

        CoordinationResult res = coord.coordinate(
                List.of("BODY"), null, gather, LlmChannel.DEFAULT).block(Duration.ofSeconds(2));

        assertThat(res).isNotNull();
        assertThat(res.text()).isEqualTo("OK");
        // only the successful, non-empty step survives
        assertThat(res.gathered().has("budget")).isTrue();
        assertThat(res.gathered().has("weather")).isFalse();
        assertThat(res.gathered().has("empty")).isFalse();

        String userContent = cap.getValue().messages().get(cap.getValue().messages().size() - 1).content();
        assertThat(userContent).contains("\"max\":50").doesNotContain("weather");
    }

    @Test
    void emptyGatherSynthesizesWithPayloadOnly() {
        LlmClient llm = mock(LlmClient.class);
        ArgumentCaptor<LlmChatRequest> cap = ArgumentCaptor.forClass(LlmChatRequest.class);
        when(llm.chat(any()))
                .thenReturn(Mono.just(new LlmChatResponse("m", "REPLY", "stop", null)));

        Coordinator coord = new Coordinator(llm, json);

        CoordinationResult res = coord.coordinate(
                List.of("BODY"), json.createObjectNode().put("q", "hi"), Map.of(), LlmChannel.FAST)
                .block(Duration.ofSeconds(2));

        assertThat(res).isNotNull();
        assertThat(res.text()).isEqualTo("REPLY");
        assertThat(res.gathered().isEmpty()).isTrue();
    }
}
