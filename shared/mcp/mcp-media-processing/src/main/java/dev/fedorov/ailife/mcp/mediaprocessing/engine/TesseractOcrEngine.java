package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import dev.fedorov.ailife.contracts.media.OcrResult;
import dev.fedorov.ailife.mcp.mediaprocessing.config.McpMediaProcessingProperties;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Real OCR via Tess4J (JNI wrapper around native {@code tesseract-ocr}, MP-b). The
 * native lib + language data are installed in the Docker image (owner-chosen "Tess4J
 * in-image", 2026-06-19) and in CI for the real-OCR test. This is the deployed default;
 * {@link StubOcrEngine} is selected only when {@code mediaprocessing.ocr-engine=stub}.
 *
 * <p>{@code tessdata} location is resolved once at startup: explicit
 * {@code mediaprocessing.tess-data-path} → {@code TESSDATA_PREFIX} env → a probe of the
 * common distro paths (so the same image/CI works across tesseract 4/5 layouts). Bean
 * creation never touches the native lib — that happens at the first {@link #extract}.
 */
@Component
@ConditionalOnProperty(name = "mediaprocessing.ocr-engine", havingValue = "tesseract", matchIfMissing = true)
public class TesseractOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);

    /** Common tessdata locations across distros / tesseract major versions. */
    private static final String[] CANDIDATE_PATHS = {
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tessdata",
            "/usr/local/share/tessdata",
            "/opt/homebrew/share/tessdata"
    };

    private final String language;
    private final String dataPath;

    public TesseractOcrEngine(McpMediaProcessingProperties props) {
        this.language = props.getTessLang();
        this.dataPath = resolveDataPath(props.getTessDataPath());
        log.info("TesseractOcrEngine ready: language={} dataPath={}", language,
                dataPath == null ? "<native default>" : dataPath);
    }

    @Override
    public OcrResult extract(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            return new OcrResult("", null, null);
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            // Unreadable/unsupported bytes are not an engine failure — no text.
            log.warn("OCR: could not decode image ({} bytes, {})", bytes.length, mimeType);
            return new OcrResult("", null, null);
        }
        if (image == null) {
            log.warn("OCR: unsupported image format ({} bytes, {})", bytes.length, mimeType);
            return new OcrResult("", null, null);
        }
        // Tess4J Tesseract is not thread-safe — one instance per call (cheap vs OCR).
        ITesseract tess = new Tesseract();
        tess.setLanguage(language);
        if (dataPath != null) {
            tess.setDatapath(dataPath);
        }
        try {
            String text = tess.doOCR(image);
            String stripped = text == null ? "" : text.strip();
            return new OcrResult(stripped, null, confidenceFor(tess, image, stripped));
        } catch (TesseractException e) {
            // Genuine engine failure (native lib / tessdata missing) — surface it.
            throw new IllegalStateException("OCR failed: " + e.getMessage(), e);
        }
    }

    /**
     * A 0..1 recognition confidence = mean Tesseract per-word confidence (0..100) ÷ 100 — the OCR twin
     * of the whisper STT signal (#489 RU-4). {@code 0.0} on empty text; {@code null} when the engine
     * reports no per-word confidence (treated by the consumer as "unknown", never low). Uses a second
     * recognition pass ({@code getWords}) so the stored text corpus stays byte-identical to {@code doOCR}'s;
     * OCR is infrequent on the docs ingest path, so the extra pass is an acceptable cost for a clean signal.
     * Best-effort: a hiccup in the word pass never fails the OCR — it just yields {@code null}.
     */
    private Double confidenceFor(ITesseract tess, BufferedImage image, String text) {
        if (text.isEmpty()) {
            return 0.0;
        }
        try {
            List<Word> words = tess.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            if (words == null || words.isEmpty()) {
                return null;
            }
            double sum = 0.0;
            int n = 0;
            for (Word w : words) {
                float c = w.getConfidence();
                if (c >= 0) {
                    sum += c;
                    n++;
                }
            }
            if (n == 0) {
                return null;
            }
            double confidence = (sum / n) / 100.0;
            return Math.max(0.0, Math.min(1.0, confidence));
        } catch (Exception e) {
            log.debug("OCR confidence pass failed, reporting no signal: {}", e.toString());
            return null;
        }
    }

    private static String resolveDataPath(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String env = System.getenv("TESSDATA_PREFIX");
        if (env != null && !env.isBlank()) {
            return env;
        }
        for (String candidate : CANDIDATE_PATHS) {
            if (new File(candidate, "eng.traineddata").isFile()) {
                return candidate;
            }
        }
        // Leave null → native default ("./tessdata"); extract() will report a clear
        // error if the data truly isn't there.
        return null;
    }
}
