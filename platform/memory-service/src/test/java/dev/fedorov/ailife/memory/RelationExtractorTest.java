package dev.fedorov.ailife.memory;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.memory.capture.ExtractedRelation;
import dev.fedorov.ailife.memory.capture.RelationExtractor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests the lenient JSON parsing of {@link RelationExtractor} with a mocked
 * {@link LlmClient} — no Docker, no llm-gateway.
 */
class RelationExtractorTest {

    private final ObjectMapper json = new ObjectMapper();

    private RelationExtractor returning(String content) {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(new LlmChatResponse("mock", content, "stop", null)));
        return new RelationExtractor(llm, json);
    }

    @Test
    void parsesCleanJson() {
        assertThat(returning("""
                {"relations":[{"subject":"self","edge":"likes","object":"jazz"},
                              {"subject":"Maria","edge":"works_at","object":"Google"}]}""").extract("x"))
                .containsExactly(
                        new ExtractedRelation("self", "likes", "jazz"),
                        new ExtractedRelation("Maria", "works_at", "Google"));
    }

    @Test
    void stripsMarkdownFences() {
        assertThat(returning("```json\n{\"relations\":[{\"subject\":\"Vlad\",\"edge\":\"owns\",\"object\":\"bike\"}]}\n```").extract("x"))
                .containsExactly(new ExtractedRelation("Vlad", "owns", "bike"));
    }

    @Test
    void toleratesLeadingProse() {
        assertThat(returning("Sure! {\"relations\":[{\"subject\":\"Anna\",\"edge\":\"likes\",\"object\":\"чай\"}]}").extract("x"))
                .containsExactly(new ExtractedRelation("Anna", "likes", "чай"));
    }

    @Test
    void skipsTriplesMissingAField() {
        assertThat(returning("""
                {"relations":[{"subject":"","edge":"likes","object":"jazz"},
                              {"subject":"Maria","edge":"","object":"books"},
                              {"subject":"Lev","edge":"likes","object":""},
                              {"subject":"Kate","edge":"likes","object":"hiking"}]}""").extract("x"))
                .containsExactly(new ExtractedRelation("Kate", "likes", "hiking"));
    }

    @Test
    void emptyRelationsYieldsEmptyList() {
        assertThat(returning("{\"relations\":[]}").extract("x")).isEmpty();
    }

    @Test
    void nonJsonYieldsEmptyList() {
        assertThat(returning("No durable relation here.").extract("x")).isEmpty();
    }

    @Test
    void blankTextSkipsLlmEntirely() {
        LlmClient llm = mock(LlmClient.class);
        RelationExtractor extractor = new RelationExtractor(llm, json);
        assertThat(extractor.extract("   ")).isEmpty();
        verify(llm, never()).chat(any());
    }
}
