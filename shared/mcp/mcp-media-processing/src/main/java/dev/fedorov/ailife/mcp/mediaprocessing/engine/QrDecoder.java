package dev.fedorov.ailife.mcp.mediaprocessing.engine;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import dev.fedorov.ailife.contracts.media.QrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * Reads a barcode out of image bytes (MP-f). Pure Java (ZXing) — no native lib, no model, so unlike
 * {@link OcrEngine} / {@link SttEngine} / {@link FrameExtractor} there is no seam and no stub twin:
 * there is nothing environment-dependent to degrade to.
 *
 * <p>Returns an <b>empty payload</b> when no code is found. That is the normal answer for a blurred
 * shot or a label out of frame — the caller (inventory-agent) asks for another photo; it is not an
 * error condition. Only the first code in the frame is returned.
 */
@Component
public class QrDecoder {

    private static final Logger log = LoggerFactory.getLogger(QrDecoder.class);

    private static final QrResult NOTHING = new QrResult("", null);

    /**
     * TRY_HARDER and ALSO_INVERTED are load-bearing here: the input is a <b>photo of a label</b>
     * taken at an angle, not a clean render, and a printed label may be dark-on-light or the
     * reverse. PURE_BARCODE is deliberately absent — it assumes an exact, unrotated image.
     */
    private static final Map<DecodeHintType, Object> HINTS = Map.of(
            DecodeHintType.TRY_HARDER, Boolean.TRUE,
            DecodeHintType.ALSO_INVERTED, Boolean.TRUE);

    public QrResult decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return NOTHING;

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.debug("QR decode: undecodable image bytes ({})", e.toString());
            return NOTHING;
        }
        if (image == null) return NOTHING;

        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        try {
            Result result = new MultiFormatReader().decode(bitmap, HINTS);
            return new QrResult(result.getText(), result.getBarcodeFormat().name());
        } catch (NotFoundException e) {
            return NOTHING;
        }
    }
}
