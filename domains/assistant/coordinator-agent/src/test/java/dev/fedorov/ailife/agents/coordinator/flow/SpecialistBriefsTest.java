package dev.fedorov.ailife.agents.coordinator.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agents.coordinator.config.CoordinatorAgentProperties;
import dev.fedorov.ailife.agents.coordinator.config.CoordinatorAgentProperties.Specialist;
import dev.fedorov.ailife.contracts.agent.AgentActionRequest;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the live specialist-gather leg (#290, Slice B2): the FAST planning parse (tolerating
 * prose around the JSON, unknown names, case-drift, duplicates) and the per-specialist soft-fail through
 * the hub. The HTTP happy path is proven end-to-end in {@link MultiDomainCoordinatorTest}; here we mock
 * the {@link LlmClient} planner and the {@link OrchestratorInvokeClient} hub to nail the edge behaviour.
 */
class SpecialistBriefsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final OrchestratorInvokeClient hub = mock(OrchestratorInvokeClient.class);
    private final LlmClient llm = mock(LlmClient.class);

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private CoordinatorAgentProperties propsWith(String... names) {
        CoordinatorAgentProperties props = new CoordinatorAgentProperties();
        props.setSpecialists(java.util.Arrays.stream(names).map(n -> {
            Specialist s = new Specialist();
            s.setName(n);
            s.setExpertise(n + " expertise");
            return s;
        }).toList());
        return props;
    }

    private SpecialistBriefs briefs(CoordinatorAgentProperties props) {
        return new SpecialistBriefs(hub, llm, props, json);
    }

    private void plannerReplies(String content) {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                new LlmChatResponse("mock-fast", content, "stop", new LlmUsage(20, 4, 24))));
    }

    private void hubAnswers(String agent, String answer) {
        when(hub.invoke(any(AgentActionRequest.class), any(Duration.class))).thenReturn(Mono.just(
                AgentActionResult.ok(json.createObjectNode().put("agent", agent).put("answer", answer))));
    }

    @Test
    void picksKnownNamesFromNoisyPlanReplyAndFoldsAnswers() {
        plannerReplies("Sure! The relevant ones are [\"Finance\", \"finance\", \"weather\"].");
        hubAnswers("finance", "Gifts budget has 15000 RUB left.");

        JsonNode node = briefs(propsWith("finance", "calendar")).gather(HOUSEHOLD, USER, "gift budget?")
                .block(Duration.ofSeconds(2));

        // "weather" is not on the roster (dropped); "Finance"/"finance" collapse to one canonical name.
        assertThat(node).isNotNull();
        assertThat(node.get("finance").asText()).isEqualTo("Gifts budget has 15000 RUB left.");
        assertThat(node.has("calendar")).isFalse();

        // The hub was asked exactly once, for finance's read-only brief carrying the question.
        ArgumentCaptor<AgentActionRequest> captor = ArgumentCaptor.forClass(AgentActionRequest.class);
        verify(hub).invoke(captor.capture(), any(Duration.class));
        AgentActionRequest req = captor.getValue();
        assertThat(req.targetAgent()).isEqualTo("finance");
        assertThat(req.action()).isEqualTo("brief");
        assertThat(req.householdId()).isEqualTo(HOUSEHOLD);
        assertThat(req.args().get("question").asText()).isEqualTo("gift budget?");
    }

    @Test
    void emptyRosterShortCircuitsWithoutPlanningOrHub() {
        StepVerifier.create(briefs(propsWith()).gather(HOUSEHOLD, USER, "anything"))
                .verifyComplete(); // Mono.empty → coordinator context stays memory-only
        verifyNoInteractions(llm, hub);
    }

    @Test
    void plannerPicksNothingSkipsTheHub() {
        plannerReplies("[]");

        StepVerifier.create(briefs(propsWith("finance")).gather(HOUSEHOLD, USER, "pure calendar question"))
                .verifyComplete();
        verify(hub, never()).invoke(any(), any());
    }

    @Test
    void specialistThatSoftFailsIsOmitted() {
        plannerReplies("[\"finance\"]");
        when(hub.invoke(any(AgentActionRequest.class), any(Duration.class)))
                .thenReturn(Mono.just(AgentActionResult.error("mcp-finance down")));

        StepVerifier.create(briefs(propsWith("finance")).gather(HOUSEHOLD, USER, "budget?"))
                .verifyComplete(); // the only picked specialist failed → nothing to fold → empty
    }

    @Test
    void plannerErrorDowngradesToNoBriefs() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.error(new RuntimeException("gateway 500")));

        StepVerifier.create(briefs(propsWith("finance")).gather(HOUSEHOLD, USER, "budget?"))
                .verifyComplete();
        verify(hub, never()).invoke(any(), any());
    }

    @Test
    void blankQueryShortCircuits() {
        StepVerifier.create(briefs(propsWith("finance")).gather(HOUSEHOLD, USER, "  "))
                .verifyComplete();
        verifyNoInteractions(llm, hub);
    }

    @Test
    void plannerRequestUsesFastChannel() {
        plannerReplies("[]");
        briefs(propsWith("finance")).gather(HOUSEHOLD, USER, "q").block(Duration.ofSeconds(2));

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llm).chat(captor.capture());
        assertThat(captor.getValue().channel()).isEqualTo(dev.fedorov.ailife.contracts.llm.LlmChannel.FAST);
    }
}
