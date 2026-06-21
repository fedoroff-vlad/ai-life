package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Text transcribed from a stored audio/video media object by the
 * {@code mcp-media-processing} capability's {@code transcribe} (STT) tool.
 * {@code text} is the recognised speech (may be empty when nothing was heard);
 * {@code lang} is the detected language tag when the engine reports one;
 * {@code durationSeconds} is the source media duration when the engine reports it.
 * The capability returns text only — interpreting it (a voice note's intent, a
 * dictated expense) is the calling agent's skill, not this tool's job. Mirror of
 * {@link OcrResult} for the audio path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscriptResult(
        String text,
        String lang,
        Double durationSeconds) {
}
