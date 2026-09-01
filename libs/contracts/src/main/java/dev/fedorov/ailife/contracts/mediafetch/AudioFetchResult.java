package dev.fedorov.ailife.contracts.mediafetch;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of the {@code mcp-media-fetch} {@code fetch_audio} tool: audio was extracted from the URL
 * (yt-dlp {@code -x}) and uploaded to media-service, so understanding is the existing id-based
 * {@code mcp-media-processing} {@code transcribe(mediaId)}. {@code mediaId} is the stored object's id
 * (as a string — the form the media-processing tools take); it is {@code null} when no audio could be
 * obtained (no stream, error, timeout) — the signal for the caller to fall through to the visual tier.
 * {@code title} / {@code source} (yt-dlp extractor) / {@code durationSeconds} are best-effort metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AudioFetchResult(
        String mediaId,
        String title,
        String source,
        Integer durationSeconds) {

    /** No audio obtained — the caller falls through to the visual tier. */
    public static AudioFetchResult empty() {
        return new AudioFetchResult(null, null, null, null);
    }
}
