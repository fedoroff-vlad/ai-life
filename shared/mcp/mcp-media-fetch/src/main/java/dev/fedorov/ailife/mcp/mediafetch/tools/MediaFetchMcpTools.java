package dev.fedorov.ailife.mcp.mediafetch.tools;

import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.mediafetch.AudioFetchResult;
import dev.fedorov.ailife.contracts.mediafetch.VideoTranscript;
import dev.fedorov.ailife.mcp.mediafetch.engine.AudioFetchEngine;
import dev.fedorov.ailife.mcp.mediafetch.engine.ExtractedAudio;
import dev.fedorov.ailife.mcp.mediafetch.engine.VideoTranscriptEngine;
import dev.fedorov.ailife.mcp.mediafetch.http.MediaStoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The media-acquisition toolbox: pull media content out of a URL via yt-dlp. {@code transcribe_video}
 * returns a video's spoken text from its subtitles/captions — the cheapest way to read video content
 * (no download, no STT), the fast-path when captions exist. {@code fetch_audio} handles the no-captions
 * case: it extracts audio only, stores it in media-service, and returns the {@code mediaId} so
 * understanding is the existing id-based {@code mcp-media-processing} STT. The capability returns raw
 * retrieval only — no LLM — so the calling agent does the synthesis. Any agent binds this server over
 * MCP/SSE; the deterministic path goes through the {@code /internal/*} HTTP passthroughs.
 */
@Component
public class MediaFetchMcpTools {

    private static final Logger log = LoggerFactory.getLogger(MediaFetchMcpTools.class);

    private final VideoTranscriptEngine transcriber;
    private final AudioFetchEngine audioFetcher;
    private final MediaStoreClient mediaStore;

    public MediaFetchMcpTools(VideoTranscriptEngine transcriber, AudioFetchEngine audioFetcher,
                              MediaStoreClient mediaStore) {
        this.transcriber = transcriber;
        this.audioFetcher = audioFetcher;
        this.mediaStore = mediaStore;
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

    @Tool(description = """
            Extract the audio track of a video by URL (yt-dlp) and store it, returning a mediaId. Use
            this for the no-captions case: when 'transcribe_video' returns empty text, fetch the audio
            here and then run speech-to-text on the returned mediaId with the media-processing
            'transcribe' tool. Returns an empty result (null mediaId) when no audio can be obtained —
            fall through to the visual tier. Downloads audio only, not the video.
            """)
    public AudioFetchResult fetch_audio(String url, UUID householdId, UUID ownerId) {
        if (url == null || url.isBlank() || householdId == null) {
            return AudioFetchResult.empty();
        }
        ExtractedAudio audio = audioFetcher.fetch(url);
        if (audio.isEmpty()) {
            return AudioFetchResult.empty();
        }
        try {
            MediaObjectDto stored = mediaStore
                    .upload(householdId, ownerId, audio.filename(), audio.mimeType(), audio.bytes())
                    .block();
            if (stored == null || stored.id() == null) {
                log.warn("media-service upload returned no id for {}", url);
                return AudioFetchResult.empty();
            }
            return new AudioFetchResult(stored.id().toString(), audio.title(), audio.source(),
                    audio.durationSeconds());
        } catch (Exception e) {
            log.warn("fetch_audio upload failed for {}: {}", url, e.toString());
            return AudioFetchResult.empty();
        }
    }
}
