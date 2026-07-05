package dev.fedorov.ailife.memory;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.memory.capture.FactExtractor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests the lenient JSON parsing of {@link FactExtractor} with a mocked
 * {@link LlmClient} — no Docker, no llm-gateway.
 */
class FactExtractorTest {

    private final ObjectMapper json = new ObjectMapper();

    private FactExtractor returning(String content) {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(new LlmChatResponse("mock", content, "stop", null)));
        return new FactExtractor(llm, json);
    }

    @Test
    void parsesCleanJson() {
        assertThat(returning("{\"facts\":[\"Maria likes tea.\",\"Vlad cycles.\"]}").extract("x"))
                .containsExactly("Maria likes tea.", "Vlad cycles.");
    }

    @Test
    void stripsMarkdownFences() {
        assertThat(returning("```json\n{\"facts\":[\"A.\"]}\n```").extract("x"))
                .containsExactly("A.");
    }

    @Test
    void toleratesLeadingProse() {
        assertThat(returning("Sure! {\"facts\":[\"B.\"]}").extract("x"))
                .containsExactly("B.");
    }

    @Test
    void emptyFactsYieldsEmptyList() {
        assertThat(returning("{\"facts\":[]}").extract("x")).isEmpty();
    }

    @Test
    void nonJsonYieldsEmptyList() {
        assertThat(returning("I could not find anything durable.").extract("x")).isEmpty();
    }

    @Test
    void blankTextSkipsLlmEntirely() {
        LlmClient llm = mock(LlmClient.class);
        FactExtractor extractor = new FactExtractor(llm, json);
        assertThat(extractor.extract("   ")).isEmpty();
        verify(llm, never()).chat(any());
    }
}
