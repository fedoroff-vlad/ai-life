package dev.fedorov.ailife.memory.eval;

import dev.fedorov.ailife.memory.capture.CaptureOutcome;
import dev.fedorov.ailife.memory.capture.NoteCandidate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tiny, pure precision/recall scorer for the ambient-capture three-way classification (road-test #488,
 * <b>MQ-3</b> — ambient-precision tuning; plans/ambient-capture.md §MQ-3). It accumulates
 * {@code (expected, predicted)} {@link CaptureOutcome} pairs into a confusion matrix and derives the two
 * metrics the MQ-3 goals map onto:
 *
 * <ul>
 *   <li><b>act-precision</b> — of the messages the system chose to <i>act</i> on (auto-save or ask), the
 *       fraction that were genuinely note-worthy. A false positive is <b>trivia saved</b>, so high
 *       act-precision is the "trivia isn't saved" goal.</li>
 *   <li><b>act-recall</b> — of the genuinely note-worthy messages, the fraction the system acted on. A false
 *       negative is a <b>durable fact missed</b>, so high act-recall is the "durable facts aren't missed"
 *       goal.</li>
 * </ul>
 *
 * "act" is the binary collapse of the three-way decision: {@link CaptureOutcome#EXPLICIT_FIXATION} and
 * {@link CaptureOutcome#IMPORTANT_INFERRED} both mean <i>act</i> (write, or ask-then-write);
 * {@link CaptureOutcome#TRIVIAL} means <i>ignore</i>. Per-class precision/recall and a legible report are
 * also exposed so a tuning run can see <i>where</i> the classifier drifts (e.g. explicit cues slipping to
 * trivial). Deterministic and model-free — the {@link GoldenAmbientPrecisionTest} feeds it real-model
 * predictions; {@link AmbientCaptureScoreTest} feeds it synthetic pairs to prove the maths can fail.
 */
public final class AmbientCaptureScore {

    /** The two outcomes that mean "act on it" (write or approve-then-write); the rest is "ignore". */
    public static final Set<CaptureOutcome> ACT =
            Set.of(CaptureOutcome.EXPLICIT_FIXATION, CaptureOutcome.IMPORTANT_INFERRED);

    // expected -> (predicted -> count)
    private final Map<CaptureOutcome, Map<CaptureOutcome, Integer>> matrix = new EnumMap<>(CaptureOutcome.class);
    private int total;

    public AmbientCaptureScore() {
        for (CaptureOutcome e : CaptureOutcome.values()) {
            matrix.put(e, new EnumMap<>(CaptureOutcome.class));
        }
    }

    /**
     * The single predicted outcome for a message: the <b>strongest</b> (most-actionable) candidate the
     * extractor emitted — {@code EXPLICIT_FIXATION} &gt; {@code IMPORTANT_INFERRED} &gt; {@code TRIVIAL} (the
     * enum's declaration order) — or {@code TRIVIAL} when it emitted nothing. Mirrors how {@code CaptureService}
     * acts on the highest-trust candidate.
     */
    public static CaptureOutcome strongest(List<NoteCandidate> candidates) {
        CaptureOutcome best = CaptureOutcome.TRIVIAL;
        if (candidates != null) {
            for (NoteCandidate c : candidates) {
                CaptureOutcome o = c.outcome();
                if (o.ordinal() < best.ordinal()) {
                    best = o;
                }
            }
        }
        return best;
    }

    /** Record one labelled/predicted pair. */
    public void add(CaptureOutcome expected, CaptureOutcome predicted) {
        matrix.get(expected).merge(predicted, 1, Integer::sum);
        total++;
    }

    public int total() {
        return total;
    }

    private int count(CaptureOutcome expected, CaptureOutcome predicted) {
        return matrix.get(expected).getOrDefault(predicted, 0);
    }

    /** How many messages carry this expected label. */
    private int expectedCount(CaptureOutcome expected) {
        int n = 0;
        for (CaptureOutcome p : CaptureOutcome.values()) {
            n += count(expected, p);
        }
        return n;
    }

    /** How many messages the classifier predicted as this outcome. */
    private int predictedCount(CaptureOutcome predicted) {
        int n = 0;
        for (CaptureOutcome e : CaptureOutcome.values()) {
            n += count(e, predicted);
        }
        return n;
    }

    /** Per-class precision = TP / (TP+FP); vacuously 1.0 when the class was never predicted. */
    public double precision(CaptureOutcome c) {
        int predicted = predictedCount(c);
        return predicted == 0 ? 1.0 : (double) count(c, c) / predicted;
    }

    /** Per-class recall = TP / (TP+FN); vacuously 1.0 when the class never appears in the corpus. */
    public double recall(CaptureOutcome c) {
        int expected = expectedCount(c);
        return expected == 0 ? 1.0 : (double) count(c, c) / expected;
    }

    // ----- binary act-vs-ignore metrics (the MQ-3 goals) --------------------------------------------

    private int predictedActCount() {
        return ACT.stream().mapToInt(this::predictedCount).sum();
    }

    private int expectedActCount() {
        return ACT.stream().mapToInt(this::expectedCount).sum();
    }

    /** Messages where BOTH the label and the prediction fall in {@link #ACT} (the binary decision agrees). */
    private int actAgreement() {
        int n = 0;
        for (CaptureOutcome e : ACT) {
            for (CaptureOutcome p : ACT) {
                n += count(e, p);
            }
        }
        return n;
    }

    /** Of the messages predicted "act", the fraction truly note-worthy → "trivia isn't saved". */
    public double actPrecision() {
        int predicted = predictedActCount();
        return predicted == 0 ? 1.0 : (double) actAgreement() / predicted;
    }

    /** Of the genuinely note-worthy messages, the fraction acted on → "durable facts aren't missed". */
    public double actRecall() {
        int expected = expectedActCount();
        return expected == 0 ? 1.0 : (double) actAgreement() / expected;
    }

    /**
     * Of the messages labelled {@code expected}, the fraction predicted as anything in {@link #ACT} (i.e. not
     * dropped to trivial). Used to assert an explicit "запомни" cue is essentially never missed.
     */
    public double notTrivialRecall(CaptureOutcome expected) {
        int total = expectedCount(expected);
        if (total == 0) {
            return 1.0;
        }
        int caught = ACT.stream().mapToInt(p -> count(expected, p)).sum();
        return (double) caught / total;
    }

    /** Exact three-class accuracy. */
    public double accuracy() {
        if (total == 0) {
            return 1.0;
        }
        int correct = 0;
        for (CaptureOutcome c : CaptureOutcome.values()) {
            correct += count(c, c);
        }
        return (double) correct / total;
    }

    /** A legible confusion matrix + metric block for the golden's stdout (so a tuning run can read it). */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ambient-capture classification (n=").append(total).append(")\n");
        sb.append(String.format("%-20s %10s %10s %10s%n", "expected \\ predicted",
                "EXPLICIT", "INFERRED", "TRIVIAL"));
        for (CaptureOutcome e : CaptureOutcome.values()) {
            sb.append(String.format("%-20s %10d %10d %10d%n", e,
                    count(e, CaptureOutcome.EXPLICIT_FIXATION),
                    count(e, CaptureOutcome.IMPORTANT_INFERRED),
                    count(e, CaptureOutcome.TRIVIAL)));
        }
        sb.append(String.format("accuracy=%.2f  act-precision=%.2f  act-recall=%.2f%n",
                accuracy(), actPrecision(), actRecall()));
        sb.append(String.format("explicit not-missed=%.2f  inferred not-missed=%.2f%n",
                notTrivialRecall(CaptureOutcome.EXPLICIT_FIXATION),
                notTrivialRecall(CaptureOutcome.IMPORTANT_INFERRED)));
        return sb.toString();
    }
}
