package dev.fedorov.ailife.mcp.mediafetch.tools;

import dev.fedorov.ailife.contracts.mediafetch.VideoTranscript;
import dev.fedorov.ailife.mcp.mediafetch.engine.VideoTranscriptEngine;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The media-acquisition toolbox: pull media content out of a URL via yt-dlp. {@code transcribe_video}
 * returns a video's spoken text from its subtitles/captions — the cheapest way to read video content
 * (no download, no STT), the fast-path when captions exist. The capability returns raw retrieval only —
 * no LLM — so the calling agent does the synthesis. Any agent binds this server over MCP/SSE; the
 * deterministic path goes through the {@code /internal/*} HTTP passthroughs.
 */
@Component
public class MediaFetchMcpTools {

    private final VideoTranscriptEngine transcriber;

    public MediaFetchMcpTools(VideoTranscriptEngine transcriber) {
        this.transcriber = transcriber;
    }

    @Tool(description = """
            Get the transcript (spoken text) of a video by URL — YouTube and other sites yt-dlp
            supports. Use this instead of 'fetch_url' for video links: a video page returns almost no
            readable text, but its subtitles/captions are the actual content. Returns empty text when
            the video has no transcript. The text may be truncated for very long videos. You do the
            summarising — this returns the raw transcript only.
            """)
    public VideoTranscript transcribe_video(String url, String lang) {
        if (url == null || url.isBlank()) {
            return new VideoTranscript(url, null, "", null, false);
        }
        return transcriber.transcribe(url, lang);
    }
}
