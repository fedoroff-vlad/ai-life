package dev.fedorov.ailife.mcp.mediafetch.engine;

import dev.fedorov.ailife.contracts.mediafetch.VideoTranscript;

/**
 * Pluggable video-transcript backend: given a video URL, return its spoken text from
 * subtitles/auto-captions. The default is {@link YtDlpTranscriptEngine} (yt-dlp, the same tool
 * Agent-Reach uses); selected by {@code media-fetch.transcript-engine}. Mirrors {@code OcrEngine} /
 * {@code SttEngine}. Best-effort: a URL with no transcript returns empty text rather than throwing.
 */
public interface VideoTranscriptEngine {

    VideoTranscript transcribe(String url, String lang);
}
