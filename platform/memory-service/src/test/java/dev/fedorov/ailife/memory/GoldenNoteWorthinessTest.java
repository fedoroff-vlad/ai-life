package dev.fedorov.ailife.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.memory.capture.CaptureOutcome;
import dev.fedorov.ailife.memory.capture.NoteCandidate;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 <b>golden test</b> — exercises {@link NoteWorthinessExtractor} against a <b>real model</b>
 * (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting <b>structure, not wording</b>:
 * given a natural message with a fixation cue about a person, the real model must emit a parseable
 * candidate with a plausible type/subject and the explicit-fixation classification. Mirrors
 * {@code GoldenNoteWriterTest}.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}).
 */
@GoldenLlmTest
class GoldenNoteWorthinessTest {

    private final NoteWorthinessExtractor extractor =
            new NoteWorthinessExtractor(GoldenLlm.client(), new ObjectMapper());

    @Test
    void classifiesAnExplicitFixationAboutAPerson() {
        List<NoteCandidate> out = extractor.extract("запомни, что у мамы аллергия на орехи");

        assertThat(out).as("real model produced no candidate — is llm-gateway up at %s?",
                GoldenLlm.gatewayUrl()).isNotEmpty();
        NoteCandidate c = out.get(0);
        assertThat(c.title()).isNotBlank();
        assertThat(c.body()).isNotBlank();
        assertThat(c.subject()).as("subject should name a person, not be self/null").isNotNull();
        assertThat(c.isSelf()).isFalse();
        assertThat(c.outcome()).isEqualTo(CaptureOutcome.EXPLICIT_FIXATION);
    }

    @Test
    void ignoresTrivialSmallTalk() {
        List<NoteCandidate> out = extractor.extract("привет! как дела? какая сегодня погода?");

        // Nothing durable — either no candidate at all, or none that survives as non-trivial.
        assertThat(out.stream().noneMatch(c -> c.outcome() != CaptureOutcome.TRIVIAL))
                .as("small-talk should yield no auto-save/approve candidate (got %s)", out)
                .isTrue();
    }
}
