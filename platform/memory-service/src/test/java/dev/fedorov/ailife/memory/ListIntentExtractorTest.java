package dev.fedorov.ailife.memory;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.memory.capture.ListIntentExtractor;
import dev.fedorov.ailife.memory.capture.ListItemCandidate;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-tests the lenient JSON parsing of {@link ListIntentExtractor} with a mocked {@link LlmClient} — no
 * Docker, no llm-gateway. Mirrors {@code NoteWorthinessExtractorTest} / {@code FactExtractorTest}.
 */
class ListIntentExtractorTest {

    private final ObjectMapper json = new ObjectMapper();

    private ListIntentExtractor returning(String content) {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(new LlmChatResponse("mock", content, "stop", null)));
        return new ListIntentExtractor(llm, json);
    }

    @Test
    void parsesCleanJson() {
        List<ListItemCandidate> out = returning("""
                {"items":[{"item":"молоко","list":"список покупок"}]}""").extract("надо купить молоко");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).item()).isEqualTo("молоко");
        assertThat(out.get(0).list()).isEqualTo("список покупок");
    }

    @Test
    void parsesSeveralItems() {
        List<ListItemCandidate> out = returning("""
                {"items":[{"item":"молоко","list":null},{"item":"хлеб","list":null}]}""").extract("x");
        assertThat(out).extracting(ListItemCandidate::item).containsExactly("молоко", "хлеб");
    }

    @Test
    void stripsMarkdownFences() {
        assertThat(returning("""
                ```json
                {"items":[{"item":"кофе","list":"shopping"}]}
                ```""").extract("x")).hasSize(1);
    }

    @Test
    void toleratesLeadingProse() {
        assertThat(returning("""
                Sure! {"items":[{"item":"зонт","list":null}]}""").extract("x")).hasSize(1);
    }

    @Test
    void emptyItemsYieldsEmptyList() {
        assertThat(returning("{\"items\":[]}").extract("x")).isEmpty();
    }

    @Test
    void nonJsonYieldsEmptyList() {
        assertThat(returning("No list intent here.").extract("x")).isEmpty();
    }

    @Test
    void dropsBlankItemShells() {
        assertThat(returning("""
                {"items":[{"item":"  ","list":"список покупок"}]}""").extract("x")).isEmpty();
    }

    @Test
    void nullListStringCollapsesToNull() {
        ListItemCandidate c = returning("""
                {"items":[{"item":"молоко","list":"null"}]}""").extract("x").get(0);
        assertThat(c.list()).isNull();
    }

    @Test
    void blankTextSkipsLlmEntirely() {
        LlmClient llm = mock(LlmClient.class);
        ListIntentExtractor extractor = new ListIntentExtractor(llm, json);
        assertThat(extractor.extract("   ")).isEmpty();
        verify(llm, never()).chat(any());
    }
}
