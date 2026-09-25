package dev.fedorov.ailife.agents.inventory.label;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.fedorov.ailife.contracts.inventory.BoxDeepLink;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Renders a container's printed identity: the Telegram deep-link payload and its QR PNG (IN-d).
 *
 * <p><b>A pure function → a lib-shaped class inside the agent, not an MCP tool.</b> Encoding needs no
 * external resource and owns no schema, so it costs no container and no HTTP hop (the same reasoning
 * as {@code libs/doc-render} §"Why a lib"); it lifts to {@code libs/qr} only on a second consumer.
 * Decoding is the mirror image — it reads bytes that live in media-service, which is why <i>that</i>
 * half is a capability tool ({@code mcp-media-processing.decode_qr}, MP-f).
 *
 * <p><b>The label encodes an id, never the contents.</b> The payload is the existing
 * {@code t.me/<bot>?start=<token>} deep-link shape with a {@code box_} prefix, so editing, renaming or
 * moving a container never invalidates a label already stuck on a box — the convention every analogue
 * app converged on. The prefix is what lets the gateway's existing {@code /start} parser tell a box
 * scan from a family invite (IN-f).
 *
 * <p>Output is deterministic: the same token always renders byte-identical PNG, so re-issuing a label
 * after the box was repacked hands back the very same image.
 */
public final class BoxLabelImage {

    /**
     * 58 mm at 203 dpi — the short side of the label stock the owner's printer class takes, so the
     * PNG is already the right size to print from a phone (the native TSPL template is deferred).
     */
    private static final int DEFAULT_PIXELS = 464;

    /** One module of quiet zone: the printable area is small, and QR scans fine at 1. */
    private static final int QUIET_ZONE_MODULES = 1;

    private BoxLabelImage() {
    }

    /**
     * The deep link a scanner opens: {@code https://t.me/<bot>?start=box_<qrToken>}. The shape lives in
     * {@link BoxDeepLink} (contracts) because the gateway's {@code /start} dispatch has to recognise it —
     * a prefix that drifted here would orphan every sticker already on a box.
     */
    public static String deepLink(String botUsername, String qrToken) {
        return BoxDeepLink.of(botUsername, qrToken);
    }

    /** The QR PNG for a payload, at the default print size. */
    public static byte[] png(String payload) {
        return png(payload, DEFAULT_PIXELS);
    }

    /**
     * The QR PNG for a payload. Error correction M — a label on a box gets scuffed, and the payload is
     * short enough that the extra redundancy costs no modules that matter.
     */
    public static byte[] png(String payload, int pixels) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, QUIET_ZONE_MODULES,
                    EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter()
                    .encode(payload, BarcodeFormat.QR_CODE, pixels, pixels, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("QR encode failed for a container label", e);
        }
    }
}
