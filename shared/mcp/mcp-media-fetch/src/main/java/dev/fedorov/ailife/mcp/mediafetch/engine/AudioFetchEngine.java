package dev.fedorov.ailife.mcp.mediafetch.engine;

/**
 * Pluggable audio-acquisition backend: given a media URL, extract its <b>audio</b> bytes (no video),
 * so the caller can upload them to media-service and run id-based STT. The default is
 * {@link YtDlpAudioFetchEngine} (yt-dlp {@code -x}); selected by {@code media-fetch.audio-engine}.
 * Mirrors {@link VideoTranscriptEngine}. Best-effort: a URL with no obtainable audio returns
 * {@link ExtractedAudio#none()} rather than throwing, so one bad video never sinks a research gather.
 */
public interface AudioFetchEngine {

    ExtractedAudio fetch(String url);
}
