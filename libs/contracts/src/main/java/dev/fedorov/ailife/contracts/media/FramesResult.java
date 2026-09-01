package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Result of the {@code mcp-media-processing} {@code frames} tool: {@code n} evenly-spaced keyframes
 * were extracted from a stored video (ffmpeg) and each uploaded to media-service, so understanding is
 * the existing id-based {@code caption(mediaId, instruction)} per frame. {@code frameMediaIds} are the
 * stored image object ids (as strings — the form the media-processing tools take), in temporal order;
 * the list is empty when no frame could be produced (missing bytes, extractor error, timeout) — the
 * signal that the visual tier yielded nothing. The visual-channel twin of {@link TranscriptResult}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FramesResult(
        List<String> frameMediaIds) {

    /** No frames produced — the visual tier yielded nothing. */
    public static FramesResult empty() {
        return new FramesResult(List.of());
    }
}
