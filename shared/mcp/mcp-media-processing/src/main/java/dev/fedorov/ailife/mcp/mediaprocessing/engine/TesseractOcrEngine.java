package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import dev.fedorov.ailife.contracts.media.OcrResult;
import dev.fedorov.ailife.mcp.mediaprocessing.config.McpMediaProcessingProperties;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

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
            return new OcrResult(text == null ? "" : text.strip(), null, null);
        } catch (TesseractException e) {
            // Genuine engine failure (native lib / tessdata missing) — surface it.
            throw new IllegalStateException("OCR failed: " + e.getMessage(), e);
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
