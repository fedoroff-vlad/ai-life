package dev.fedorov.ailife.contracts.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of the {@code mcp-web} {@code transcribe_video} tool: the spoken text of a video, pulled
 * from its subtitles / auto-captions (e.g. via yt-dlp). {@code text} is empty (never null) when no
 * transcript could be obtained — a JS-rendered video page yields nothing through {@code fetch_url},
 * so this is how the research flow reads video content. {@code lang} is the subtitle language when
 * known; {@code truncated} is set when the text was capped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoTranscript(
        String url,
        String title,
        String text,
        String lang,
        boolean truncated) {
}
