package dev.fedorov.ailife.mcp.mediafetch.engine;

/**
 * Raw audio bytes an {@link AudioFetchEngine} pulled out of a media URL, plus the metadata needed to
 * store and describe them: the MIME type to store the object as, a {@code filename} (extension carries
 * the codec), the best-effort {@code title} / {@code source} (yt-dlp extractor) / {@code durationSeconds}.
 * {@link #bytes()} is empty when nothing could be obtained ({@link #none()}); {@link #isEmpty()} is the
 * "fall through to the visual tier" signal.
 */
public record ExtractedAudio(
        byte[] bytes,
        String mimeType,
        String filename,
        String title,
        String source,
        Integer durationSeconds) {

    public boolean isEmpty() {
        return bytes == null || bytes.length == 0;
    }

    /** Nothing obtained — no stream, error, or timeout. */
    public static ExtractedAudio none() {
        return new ExtractedAudio(new byte[0], null, null, null, null, null);
    }
}
