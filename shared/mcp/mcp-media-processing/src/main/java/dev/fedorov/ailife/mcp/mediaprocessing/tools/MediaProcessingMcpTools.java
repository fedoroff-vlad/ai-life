package dev.fedorov.ailife.mcp.mediaprocessing.tools;

import dev.fedorov.ailife.contracts.media.OcrResult;
import dev.fedorov.ailife.mcp.mediaprocessing.engine.OcrEngine;
import dev.fedorov.ailife.mcp.mediaprocessing.http.MediaClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * The shared media-understanding toolbox. {@code ocr} is the first tool (MP-a);
 * {@code transcribe} (STT) and {@code caption} (vision) land in MP-d. Any agent binds
 * this server over MCP/SSE and passes a media-service object id — the capability fetches
 * the bytes and runs the engine, returning extracted text only (no domain reasoning).
 */
@Component
public class MediaProcessingMcpTools {

    private final MediaClient media;
    private final OcrEngine ocr;

    public MediaProcessingMcpTools(MediaClient media, OcrEngine ocr) {
        this.media = media;
        this.ocr = ocr;
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
}
