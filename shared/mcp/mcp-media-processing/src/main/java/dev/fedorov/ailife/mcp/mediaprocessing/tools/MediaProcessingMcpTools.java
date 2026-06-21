package dev.fedorov.ailife.mcp.mediaprocessing.tools;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmImage;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.media.CaptionResult;
import dev.fedorov.ailife.contracts.media.OcrResult;
import dev.fedorov.ailife.contracts.media.TranscriptResult;
import dev.fedorov.ailife.llm.LlmClient;
import dev.fedorov.ailife.mcp.mediaprocessing.engine.OcrEngine;
import dev.fedorov.ailife.mcp.mediaprocessing.engine.SttEngine;
import dev.fedorov.ailife.mcp.mediaprocessing.http.MediaClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;

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

    private final MediaClient media;
    private final OcrEngine ocr;
    private final SttEngine stt;
    private final LlmClient llm;

    public MediaProcessingMcpTools(MediaClient media, OcrEngine ocr, SttEngine stt, LlmClient llm) {
        this.media = media;
        this.ocr = ocr;
        this.stt = stt;
        this.llm = llm;
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
}
