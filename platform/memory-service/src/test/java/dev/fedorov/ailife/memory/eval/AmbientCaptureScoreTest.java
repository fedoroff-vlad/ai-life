package dev.fedorov.ailife.memory.eval;

import dev.fedorov.ailife.memory.capture.CaptureOutcome;
import dev.fedorov.ailife.memory.capture.NoteCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Fast, model-free unit tests for {@link AmbientCaptureScore} (MQ-3, road-test #488). They feed a fixed
 * confusion of {@code (expected, predicted)} pairs and assert the derived precision/recall maths — the
 * guard that the {@link GoldenAmbientPrecisionTest} thresholds mean what they claim (a scorer that always
 * returned 1.0 would make the golden pass vacuously, so these must be able to fail).
 */
class AmbientCaptureScoreTest {

    @Test
    void computesConfusionMetricsFromAFixedMatrix() {
        AmbientCaptureScore s = new AmbientCaptureScore();
        s.add(CaptureOutcome.EXPLICIT_FIXATION, CaptureOutcome.EXPLICIT_FIXATION);   // correct
        s.add(CaptureOutcome.EXPLICIT_FIXATION, CaptureOutcome.TRIVIAL);             // explicit cue missed
        s.add(CaptureOutcome.IMPORTANT_INFERRED, CaptureOutcome.IMPORTANT_INFERRED); // correct
        s.add(CaptureOutcome.IMPORTANT_INFERRED, CaptureOutcome.TRIVIAL);            // durable fact missed
        s.add(CaptureOutcome.TRIVIAL, CaptureOutcome.TRIVIAL);                       // correct
        s.add(CaptureOutcome.TRIVIAL, CaptureOutcome.IMPORTANT_INFERRED);            // trivia saved

        assertThat(s.total()).isEqualTo(6);
        assertThat(s.accuracy()).isCloseTo(0.5, within(1e-9));

        // act = {EXPLICIT, INFERRED}: predicted-act = 3, expected-act = 4, agreement = 2.
        assertThat(s.actPrecision()).isCloseTo(2.0 / 3, within(1e-9));   // trivia-saved drags precision
        assertThat(s.actRecall()).isCloseTo(0.5, within(1e-9));         // two durable facts missed

        assertThat(s.precision(CaptureOutcome.EXPLICIT_FIXATION)).isCloseTo(1.0, within(1e-9));
        assertThat(s.recall(CaptureOutcome.EXPLICIT_FIXATION)).isCloseTo(0.5, within(1e-9));
        assertThat(s.precision(CaptureOutcome.TRIVIAL)).isCloseTo(1.0 / 3, within(1e-9));
        // Two TRIVIAL-labelled rows, one of which leaked to INFERRED → recall 1/2.
        assertThat(s.recall(CaptureOutcome.TRIVIAL)).isCloseTo(0.5, within(1e-9));

        assertThat(s.notTrivialRecall(CaptureOutcome.EXPLICIT_FIXATION)).isCloseTo(0.5, within(1e-9));
        assertThat(s.notTrivialRecall(CaptureOutcome.IMPORTANT_INFERRED)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void emptyScoreIsVacuouslyPerfect() {
        AmbientCaptureScore s = new AmbientCaptureScore();
        assertThat(s.total()).isZero();
        assertThat(s.accuracy()).isEqualTo(1.0);
        assertThat(s.actPrecision()).isEqualTo(1.0);
        assertThat(s.actRecall()).isEqualTo(1.0);
    }

    @Test
    void perfectClassificationScoresOne() {
        AmbientCaptureScore s = new AmbientCaptureScore();
        s.add(CaptureOutcome.EXPLICIT_FIXATION, CaptureOutcome.EXPLICIT_FIXATION);
        s.add(CaptureOutcome.IMPORTANT_INFERRED, CaptureOutcome.IMPORTANT_INFERRED);
        s.add(CaptureOutcome.TRIVIAL, CaptureOutcome.TRIVIAL);

        assertThat(s.accuracy()).isEqualTo(1.0);
        assertThat(s.actPrecision()).isEqualTo(1.0);
        assertThat(s.actRecall()).isEqualTo(1.0);
    }

    @Test
    void strongestPicksTheMostActionableCandidate() {
        assertThat(AmbientCaptureScore.strongest(List.of())).isEqualTo(CaptureOutcome.TRIVIAL);
        assertThat(AmbientCaptureScore.strongest(List.of(trivial(), inferred())))
                .isEqualTo(CaptureOutcome.IMPORTANT_INFERRED);
        assertThat(AmbientCaptureScore.strongest(List.of(inferred(), explicit(), trivial())))
                .isEqualTo(CaptureOutcome.EXPLICIT_FIXATION);
    }

    private static NoteCandidate explicit() {
        return new NoteCandidate("t", "fact", "b", "self", "important", true);
    }

    private static NoteCandidate inferred() {
        return new NoteCandidate("t", "fact", "b", "self", "important", false);
    }

    private static NoteCandidate trivial() {
        return new NoteCandidate("t", "fact", "b", "self", "trivial", false);
    }
}
