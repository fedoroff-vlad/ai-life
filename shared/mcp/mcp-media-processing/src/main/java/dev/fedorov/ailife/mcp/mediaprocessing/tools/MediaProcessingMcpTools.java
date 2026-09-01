package dev.fedorov.ailife.mcp.mediaprocessing.tools;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmImage;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import dev.fedorov.ailife.contracts.media.FramesResult;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.contracts.media.OcrResult;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.mcp.mediaprocessing.config.McpMediaProcessingProperties;
import dev.fedorov.ailife.mcp.mediaprocessing.engine.FrameExtractor;
import dev.fedorov.ailife.mcp.mediaprocessing.engine.OcrEngine;
import dev.fedorov.ailife.mcp.mediaprocessing.engine.SttEngine;
import dev.fedorov.ailife.mcp.mediaprocessing.http.MediaClient;
import dev.fedorov.ailife.mcp.mediaprocessing.http.MediaStoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * The shared media-understanding toolbox. {@code ocr} (MP-a/b) reads text off an image
 * with a local engine; {@code caption} (MP-d1) asks an LLM vision model about an image
 * via the centralised {@code vision} channel — so no agent re-embeds the vision call;
 * {@code transcribe} (MP-d2) turns a stored audio/video clip into text with a local STT
 * engine. Any agent binds this server over MCP/SSE and passes a media-service object id;
 * the capability fetches the bytes and returns text only (no domain reasoning — the
 * caller's skill interprets it).
 */
@Component
public class MediaProcessingMcpTools {

    private static final String DEFAULT_CAPTION_INSTRUCTION = "Describe this image.";
    private static final Logger log = LoggerFactory.getLogger(MediaProcessingMcpTools.class);

    private final MediaClient media;
    private final MediaStoreClient mediaStore;
    private final OcrEngine ocr;
    private final SttEngine stt;
    private final FrameExtractor frames;
    private final LlmClient llm;
    private final McpMediaProcessingProperties props;

    public MediaProcessingMcpTools(MediaClient media, MediaStoreClient mediaStore, OcrEngine ocr,
                                   SttEngine stt, FrameExtractor frames, LlmClient llm,
                                   McpMediaProcessingProperties props) {
        this.media = media;
        this.mediaStore = mediaStore;
        this.ocr = ocr;
        this.stt = stt;
        this.frames = frames;
        this.llm = llm;
        this.props = props;
    }

    @Tool(description = """
            Extract text from a stored image by its media-service object id (the
            storageUri an attachment carries). Fetches the bytes from media-service and
            runs OCR. Returns the recognised text plus optional detected language and
            confidence. Returns empty text when nothing is recognised. Use this for
            receipts, documents, screenshots — anything where you need the words in an
            image; interpreting them is the caller's job.
            """)
    public OcrResult ocr(String mediaId) {
        MediaClient.FetchedMedia fetched = media.fetch(mediaId).block();
        if (fetched == null) {
            return new OcrResult("", null, null);
        }
        return ocr.extract(fetched.bytes(), fetched.mimeType());
    }

    @Tool(description = """
            Transcribe speech from a stored audio or video clip by its media-service object
            id (the storageUri an attachment carries). Fetches the bytes from media-service
            and runs speech-to-text. Returns the recognised text plus optional detected
            language and source duration. Returns empty text when no speech is recognised.
            Use this for voice notes and dictated messages — anything where you need the
            words spoken in a clip; interpreting them is the caller's job.
            """)
    public TranscriptResult transcribe(String mediaId) {
        MediaClient.FetchedMedia fetched = media.fetch(mediaId).block();
        if (fetched == null) {
            return new TranscriptResult("", null, null);
        }
        return stt.transcribe(fetched.bytes(), fetched.mimeType());
    }

    @Tool(description = """
            Ask an LLM vision model about a stored image by its media-service object id.
            Provide an 'instruction' describing what you want — a free-form description, or
            a structured extraction (e.g. "Return JSON with amount, currency, merchant, date
            from this receipt"). Returns the model's text answer. Prefer this over 'ocr' when
            you need understanding/structure rather than just the raw words; the caller parses
            and interprets the returned text.
            """)
    public CaptionResult caption(String mediaId, String instruction) {
        MediaClient.FetchedMedia fetched = media.fetch(mediaId).block();
        if (fetched == null) {
            return new CaptionResult("", null);
        }
        String prompt = (instruction == null || instruction.isBlank())
                ? DEFAULT_CAPTION_INSTRUCTION : instruction;
        String base64 = Base64.getEncoder().encodeToString(fetched.bytes());
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.VISION, List.of(
                LlmMessage.userWithImages(prompt,
                        List.of(new LlmImage(fetched.mimeType(), base64)))));
        LlmChatResponse resp = llm.chat(req).block();
        if (resp == null) {
            return new CaptionResult("", null);
        }
        return new CaptionResult(resp.content() == null ? "" : resp.content(), resp.model());
    }

    @Tool(description = """
            Extract evenly-spaced keyframes from a stored video by its media-service object id and store
            each as an image, returning the frames' media ids. Use this as the visual channel for a video
            with no informative speech (when 'transcribe' returns empty): run 'caption' on each returned
            frame id to understand what the scene shows. Provide 'n' (how many frames). Returns an empty
            list when no frame can be produced — the signal the visual tier yielded nothing. The extracted
            frames are stored under the given householdId/ownerId scope.
            """)
    public FramesResult frames(String mediaId, int n, UUID householdId, UUID ownerId) {
        if (mediaId == null || mediaId.isBlank() || householdId == null) {
            return FramesResult.empty();
        }
        int count = Math.max(1, Math.min(n, props.getFrameMaxCount()));
        MediaClient.FetchedMedia fetched = media.fetch(mediaId).block();
        if (fetched == null || fetched.bytes() == null || fetched.bytes().length == 0) {
            return FramesResult.empty();
        }
        List<byte[]> extracted = frames.extract(fetched.bytes(), fetched.mimeType(), count);
        if (extracted.isEmpty()) {
            return FramesResult.empty();
        }
        List<String> ids = new ArrayList<>(extracted.size());
        for (int i = 0; i < extracted.size(); i++) {
            try {
                MediaObjectDto stored = mediaStore
                        .upload(householdId, ownerId, "frame-" + i + ".jpg", "image/jpeg", extracted.get(i))
                        .block();
                if (stored != null && stored.id() != null) {
                    ids.add(stored.id().toString());
                } else {
                    log.warn("media-service upload returned no id for frame {} of {}", i, mediaId);
                }
            } catch (Exception e) {
                log.warn("frame {} upload failed for {}: {}", i, mediaId, e.toString());
            }
        }
        return ids.isEmpty() ? FramesResult.empty() : new FramesResult(ids);
    }
}
