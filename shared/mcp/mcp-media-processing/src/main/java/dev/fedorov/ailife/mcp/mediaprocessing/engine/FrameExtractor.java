package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import java.util.List;

/**
 * Pluggable video-keyframe backend (MP-e). Given raw video bytes, returns {@code n} evenly-spaced
 * keyframes as JPEG images — the visual channel for speechless video (ASMR / landscape) where
 * {@code transcribe} returns empty. {@link StubFrameExtractor} ships a deterministic native-free
 * implementation; {@link FfmpegFrameExtractor} swaps in real ffmpeg behind this same interface, so the
 * tool layer and its tests don't change. An extractor never throws for ordinary "no frames" — it
 * returns an empty list; it may throw only on a genuine engine failure. Mirror of {@link SttEngine} for
 * the visual path.
 */
public interface FrameExtractor {

    /**
     * Extract {@code n} evenly-spaced keyframes from video bytes.
     *
     * @param bytes    the raw video bytes (already fetched from media-service)
     * @param mimeType the declared MIME type, to help the engine pick a demuxer
     * @param n        how many frames to extract (already clamped to a sane range by the caller)
     * @return the extracted JPEG frames in temporal order (possibly empty), never {@code null}
     */
    List<byte[]> extract(byte[] bytes, String mimeType, int n);
}
