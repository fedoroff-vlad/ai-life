package dev.fedorov.ailife.mcp.mediaprocessing;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.fedorov.ailife.contracts.media.QrResult;
import dev.fedorov.ailife.mcp.mediaprocessing.engine.QrDecoder;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MP-f: the decoder is pure Java, so it is unit-testable end to end — encode a real QR code, decode
 * it back. The round trip is what makes the test able to fail: a decoder that returned a canned
 * value would not survive a random payload.
 */
class QrDecoderTest {

    private final QrDecoder decoder = new QrDecoder();

    @Test
    void decodesTheDeepLinkPayloadItWasGiven() throws Exception {
        String payload = "https://t.me/ai_life_bot?start=box_a1b2c3d4e5f60718";

        QrResult result = decoder.decode(qrPng(payload, 240));

        assertThat(result.payload()).isEqualTo(payload);
        assertThat(result.format()).isEqualTo("QR_CODE");
    }

    @Test
    void decodesASmallNoisyCodeLikeAPhotographedLabel() throws Exception {
        // A label photographed at distance: small module size, off-white paper, grey specks.
        String payload = "box_0f1e2d3c4b5a6978";
        byte[] png = noisy(qrPng(payload, 120));

        assertThat(decoder.decode(png).payload()).isEqualTo(payload);
    }

    @Test
    void returnsEmptyPayloadWhenThereIsNoCodeInFrame() throws Exception {
        BufferedImage blank = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 200);
        g.dispose();

        QrResult result = decoder.decode(toPng(blank));

        assertThat(result.payload()).isEmpty();
        assertThat(result.format()).isNull();
    }

    @Test
    void returnsEmptyPayloadForUnreadableOrAbsentBytes() {
        assertThat(decoder.decode("not an image at all".getBytes()).payload()).isEmpty();
        assertThat(decoder.decode(new byte[0]).payload()).isEmpty();
        assertThat(decoder.decode(null).payload()).isEmpty();
    }

    /** A QR PNG of the given payload, {@code size} px square. */
    static byte[] qrPng(String payload, int size) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                        EncodeHintType.MARGIN, 2));
        return toPng(MatrixToImageWriter.toBufferedImage(matrix));
    }

    private static byte[] toPng(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Deterministic speckle + off-white cast, standing in for a camera shot of printed paper. */
    private static byte[] noisy(byte[] png) throws Exception {
        BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(png));
        java.util.Random rnd = new java.util.Random(42);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int delta = rnd.nextInt(24) - 12;
                int r = clamp(((rgb >> 16) & 0xFF) + delta);
                int g = clamp(((rgb >> 8) & 0xFF) + delta);
                int b = clamp((rgb & 0xFF) + delta - 6); // warm, paper-like cast
                src.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return toPng(src);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
