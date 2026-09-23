package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

// wire-contract-exempt: capability-MCP tool output, proven by InternalQrControllerTest + QrDecoderTest
// (the twin of OcrResult, whose passthrough it mirrors).

/**
 * What was encoded in a barcode found in a stored image, from the {@code decode_qr} tool.
 * {@code payload} is the decoded text — **empty when no code was found**, which is a normal answer
 * (a blurred shot, a label out of frame), not an error; the caller decides whether to ask for
 * another photo. {@code format} names the symbology ZXing recognised ({@code QR_CODE},
 * {@code EAN_13}, …) and is null when nothing was read. Only the first code in the frame is
 * returned. Mirror of {@link OcrResult} for the barcode path — the capability returns the payload,
 * never interprets it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QrResult(
        String payload,
        String format) {
}
