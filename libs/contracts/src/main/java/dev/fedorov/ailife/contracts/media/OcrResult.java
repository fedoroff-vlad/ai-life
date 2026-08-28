package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Text extracted from a stored media object by the {@code mcp-media-processing}
 * capability's {@code ocr} tool. {@code text} is the raw recognised text (may be
 * empty when nothing was found); {@code lang} is the detected language tag when
 * the engine reports one; {@code confidence} is the engine's 0..1 confidence when
 * available (Tesseract: mean per-word confidence; {@code 0.0} on empty text; {@code null}
 * when the engine gives no signal). docs-agent uses it to ask for a clearer shot when a
 * document photo is unreadable (#489 RU-4) — a null confidence is "unknown", never low.
 * The capability returns text only — interpreting it (a receipt amount, a sick-note date)
 * is the calling agent's skill, not this tool's job. Mirror of {@link TranscriptResult}
 * for the image path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OcrResult(
        String text,
        String lang,
        Double confidence) {
}
