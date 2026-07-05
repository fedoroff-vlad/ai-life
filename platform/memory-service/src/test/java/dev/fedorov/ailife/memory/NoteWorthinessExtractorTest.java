package dev.fedorov.ailife.memory;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.memory.capture.CaptureOutcome;
import dev.fedorov.ailife.memory.capture.NoteCandidate;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
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
 * Unit-tests the lenient JSON parsing and three-way classification of {@link NoteWorthinessExtractor} with
 * a mocked {@link LlmClient} — no Docker, no llm-gateway. Mirrors {@code FactExtractorTest}.
 */
class NoteWorthinessExtractorTest {

    private final ObjectMapper json = new ObjectMapper();

    private NoteWorthinessExtractor returning(String content) {
        LlmClient llm = mock(LlmClient.class);
        when(llm.chat(any(LlmChatRequest.class)))
                .thenReturn(Mono.just(new LlmChatResponse("mock", content, "stop", null)));
        return new NoteWorthinessExtractor(llm, json);
    }

    @Test
    void parsesCleanJson() {
        List<NoteCandidate> out = returning("""
                {"candidates":[{"title":"Мама — аллергия","type":"person","body":"У мамы аллергия на орехи.",
                "subject":"Мама","importance":"important","explicitFixation":false}]}""").extract("x");
        assertThat(out).hasSize(1);
        NoteCandidate c = out.get(0);
        assertThat(c.title()).isEqualTo("Мама — аллергия");
        assertThat(c.type()).isEqualTo("person");
        assertThat(c.subject()).isEqualTo("Мама");
        assertThat(c.isSelf()).isFalse();
    }

    @Test
    void stripsMarkdownFences() {
        assertThat(returning("""
                ```json
                {"candidates":[{"title":"A","body":"a","subject":"self","importance":"important","explicitFixation":true}]}
                ```""").extract("x")).hasSize(1);
    }

    @Test
    void toleratesLeadingProse() {
        assertThat(returning("""
                Sure! {"candidates":[{"title":"A","body":"a","subject":null,"importance":"trivial","explicitFixation":false}]}""")
                .extract("x")).hasSize(1);
    }

    @Test
    void emptyCandidatesYieldsEmptyList() {
        assertThat(returning("{\"candidates\":[]}").extract("x")).isEmpty();
    }

    @Test
    void nonJsonYieldsEmptyList() {
        assertThat(returning("I could not find anything worth a note.").extract("x")).isEmpty();
    }

    @Test
    void dropsEmptyShellCandidates() {
        assertThat(returning("""
                {"candidates":[{"title":"","body":"  ","subject":"self","importance":"important","explicitFixation":true}]}""")
                .extract("x")).isEmpty();
    }

    @Test
    void blankTextSkipsLlmEntirely() {
        LlmClient llm = mock(LlmClient.class);
        NoteWorthinessExtractor extractor = new NoteWorthinessExtractor(llm, json);
        assertThat(extractor.extract("   ")).isEmpty();
        verify(llm, never()).chat(any());
    }

    @Test
    void nullSubjectStringCollapsesToNull() {
        NoteCandidate c = returning("""
                {"candidates":[{"title":"A","body":"a","subject":"null","importance":"trivial","explicitFixation":false}]}""")
                .extract("x").get(0);
        assertThat(c.subject()).isNull();
        assertThat(c.isSelf()).isFalse();
    }

    // --- three-way classification --------------------------------------------------------------------

    @Test
    void explicitFixationWinsRegardlessOfImportance() {
        NoteCandidate c = returning("""
                {"candidates":[{"title":"A","body":"a","subject":"self","importance":"trivial","explicitFixation":true}]}""")
                .extract("x").get(0);
        assertThat(c.outcome()).isEqualTo(CaptureOutcome.EXPLICIT_FIXATION);
    }

    @Test
    void importantInferredWhenNoFixation() {
        NoteCandidate c = returning("""
                {"candidates":[{"title":"A","body":"a","subject":"self","importance":"important","explicitFixation":false}]}""")
                .extract("x").get(0);
        assertThat(c.outcome()).isEqualTo(CaptureOutcome.IMPORTANT_INFERRED);
    }

    @Test
    void trivialWhenNeitherFixationNorImportant() {
        NoteCandidate c = returning("""
                {"candidates":[{"title":"A","body":"a","subject":"self","importance":"trivial","explicitFixation":false}]}""")
                .extract("x").get(0);
        assertThat(c.outcome()).isEqualTo(CaptureOutcome.TRIVIAL);
    }
}
