package dev.fedorov.ailife.docrender;

/**
 * The concrete output of a {@link DocRenderer}: the rendered bytes plus their MIME type and a
 * suggested filename. A consumer stores these (e.g. in media-service) and hands the user a link.
 * HTML today; a PDF renderer drops in behind the same seam without touching the callers.
 */
public record RenderedDoc(byte[] content, String mimeType, String filename) {
}
