package dev.fedorov.ailife.memory.eval;

import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.memory.capture.CaptureOutcome;
import dev.fedorov.ailife.memory.capture.NoteWorthinessExtractor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MQ-3 <b>precision/recall golden</b> (road-test <a href="https://github.com/fedoroff-vlad/ai-life/issues/488">#488</a>,
 * plans/ambient-capture.md §MQ-3): runs {@link NoteWorthinessExtractor} against a <b>real model</b> over the
 * labelled {@code ambient/precision-corpus.json} and asserts the two ambient-capture quality goals as
 * thresholds, so the classifier can be <i>measured and tuned</i> instead of judged on three anecdotes
 * ({@code GoldenNoteWorthinessTest}). It reduces each message to its strongest predicted
 * {@link CaptureOutcome} and scores it with the pure {@link AmbientCaptureScore}.
 *
 * <p>The gate on flipping {@code MEMORY_AMBIENT_CAPTURE_ENABLED} on: this passes on the target model. The
 * thresholds are the acceptance bar, not a description of the current default model —
 * {@link #ACT_PRECISION_MIN} = "trivia isn't saved", {@link #ACT_RECALL_MIN} = "durable facts aren't missed",
 * {@link #EXPLICIT_NOT_MISSED_MIN} = an explicit "запомни" cue is essentially never dropped. Tune the
 * extractor prompt / thresholds (or the corpus, as real mis-classifications surface) until it clears them on
 * the deploy model, then enable ambient capture.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); run with
 * {@code scripts/golden.sh -pl platform/memory-service -Dtest=GoldenAmbientPrecisionTest}.
 */
@GoldenLlmTest
class GoldenAmbientPrecisionTest {

    /** Of messages the system chose to act on, at least this fraction must be genuinely note-worthy. */
    private static final double ACT_PRECISION_MIN = 0.80;
    /** Of genuinely note-worthy messages, at least this fraction must be acted on (not dropped to trivial). */
    private static final double ACT_RECALL_MIN = 0.80;
    /** An explicit fixation cue ("запомни", …) must almost never be classified trivial. */
    private static final double EXPLICIT_NOT_MISSED_MIN = 0.90;

    private static final String CORPUS = "ambient/precision-corpus.json";

    private final NoteWorthinessExtractor extractor =
            new NoteWorthinessExtractor(GoldenLlm.client(), new ObjectMapper());

    @Test
    void meetsPrecisionAndRecallThresholdsOnTheCorpus() {
        List<Case> corpus = loadCorpus();
        assertThat(corpus).as("precision corpus is empty — is %s on the test classpath?", CORPUS)
                .isNotEmpty();

        AmbientCaptureScore score = new AmbientCaptureScore();
        for (Case c : corpus) {
            CaptureOutcome predicted = AmbientCaptureScore.strongest(extractor.extract(c.text()));
            score.add(c.expected(), predicted);
        }

        // Print the confusion matrix + metrics so a tuning run can see where the classifier drifts.
        System.out.println(score.report());

        assertThat(score.actPrecision())
                .as("trivia is being saved (act-precision below bar)\n%s", score.report())
                .isGreaterThanOrEqualTo(ACT_PRECISION_MIN);
        assertThat(score.actRecall())
                .as("durable facts are being missed (act-recall below bar)\n%s", score.report())
                .isGreaterThanOrEqualTo(ACT_RECALL_MIN);
        assertThat(score.notTrivialRecall(CaptureOutcome.EXPLICIT_FIXATION))
                .as("explicit fixation cues are being dropped to trivial\n%s", score.report())
                .isGreaterThanOrEqualTo(EXPLICIT_NOT_MISSED_MIN);
    }

    private List<Case> loadCorpus() {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CORPUS)) {
            if (in == null) {
                return cases;
            }
            JsonNode root = new ObjectMapper().readTree(in);
            JsonNode arr = root.get("cases");
            if (arr != null && arr.isArray()) {
                for (JsonNode c : arr) {
                    String text = c.path("text").asString("").trim();
                    String expected = c.path("expected").asString("").trim();
                    if (!text.isBlank() && !expected.isBlank()) {
                        cases.add(new Case(text, CaptureOutcome.valueOf(expected)));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not load ambient precision corpus " + CORPUS, e);
        }
        return cases;
    }

    private record Case(String text, CaptureOutcome expected) {
    }
}
