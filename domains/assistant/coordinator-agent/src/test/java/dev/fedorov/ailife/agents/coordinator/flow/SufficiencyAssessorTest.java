package dev.fedorov.ailife.agents.coordinator.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agents.coordinator.flow.SufficiencyAssessor.Assessment;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the bounded-loop self-check (#290, Slice E-later): the FAST verdict parse (tolerating
 * prose around the JSON), and the fail-safe-toward-stopping behaviour — a blank draft, an LLM error, or an
 * unparseable reply all resolve to {@link Assessment#sufficient()} so a broken judge never drives extra
 * rounds. The re-gather it gates end-to-end is proven in {@link MultiDomainCoordinatorTest}.
 */
class SufficiencyAssessorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = mock(LlmClient.class);

    private SufficiencyAssessor assessor() {
        return new SufficiencyAssessor(llm, json);
    }

    private void judgeReplies(String content) {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.just(
                new LlmChatResponse("mock-fast", content, "stop", new LlmUsage(20, 4, 24))));
    }

    @Test
    void parsesInsufficientVerdictWithMissingFocus() {
        judgeReplies("Here you go: {\"sufficient\": false, \"missing\": \"gift budget\"}");
        Assessment a = assessor().assess("что подарить маме", "Какая-то идея без бюджета.")
                .block(Duration.ofSeconds(2));
        assertThat(a).isNotNull();
        assertThat(a.sufficient()).isFalse();
        assertThat(a.missing()).isEqualTo("gift budget");
    }

    @Test
    void parsesSufficientVerdict() {
        judgeReplies("{\"sufficient\": true, \"missing\": \"\"}");
        Assessment a = assessor().assess("q", "A complete grounded answer.").block(Duration.ofSeconds(2));
        assertThat(a.sufficient()).isTrue();
    }

    @Test
    void blankDraftIsSufficientWithoutCallingTheLlm() {
        Assessment a = assessor().assess("q", "   ").block(Duration.ofSeconds(2));
        assertThat(a.sufficient()).isTrue();
        verifyNoInteractions(llm);
    }

    @Test
    void unparseableReplyDowngradesToSufficient() {
        judgeReplies("I think it's probably fine, no JSON here.");
        Assessment a = assessor().assess("q", "draft").block(Duration.ofSeconds(2));
        assertThat(a.sufficient()).isTrue();
    }

    @Test
    void llmErrorDowngradesToSufficient() {
        when(llm.chat(any(LlmChatRequest.class))).thenReturn(Mono.error(new RuntimeException("gateway 500")));
        Assessment a = assessor().assess("q", "draft").block(Duration.ofSeconds(2));
        assertThat(a.sufficient()).isTrue();
    }

    @Test
    void selfCheckUsesFastChannel() {
        judgeReplies("{\"sufficient\": true}");
        assessor().assess("q", "draft").block(Duration.ofSeconds(2));

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        org.mockito.Mockito.verify(llm).chat(captor.capture());
        assertThat(captor.getValue().channel()).isEqualTo(LlmChannel.FAST);
    }
}
