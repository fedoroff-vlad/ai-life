package dev.fedorov.ailife.mcp.web.engine;

import dev.fedorov.ailife.contracts.web.VideoTranscript;

/**
 * Pluggable video-transcript backend: given a video URL, return its spoken text from
 * subtitles/auto-captions. The default is {@link YtDlpTranscriptEngine} (yt-dlp, the same tool
 * Agent-Reach uses); selected by {@code mcp-web.transcript-engine}. Mirrors {@link SearchEngine} /
 * {@code OcrEngine}. Best-effort: a URL with no transcript returns empty text rather than throwing.
 */
public interface VideoTranscriptEngine {

    VideoTranscript transcribe(String url, String lang);
}
