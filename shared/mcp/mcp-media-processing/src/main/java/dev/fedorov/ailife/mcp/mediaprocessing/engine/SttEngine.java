package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import dev.fedorov.ailife.contracts.media.TranscriptResult;

/**
 * Pluggable speech-to-text backend. MP-d2a ships a deterministic {@link StubSttEngine};
 * MP-d2b swaps in a real OSS engine (whisper) behind this same interface, so the tool
 * layer and its tests don't change. An engine never throws for ordinary "no speech
 * found" — it returns an empty {@link TranscriptResult}; it may throw only on a genuine
 * engine failure. Mirror of {@link OcrEngine} for the audio path.
 */
public interface SttEngine {

    /**
     * Transcribe speech from audio (or audio-bearing video) bytes.
     *
     * @param bytes    the raw media bytes (already fetched from media-service)
     * @param mimeType the declared MIME type, to help the engine pick a decoder
     * @return the recognised text (possibly empty), never {@code null}
     */
    TranscriptResult transcribe(byte[] bytes, String mimeType);
}
