package dev.fedorov.ailife.agents.stylist.render;

/**
 * The concrete output of a {@link StylistRenderer}: the rendered bytes plus their MIME type and a
 * suggested filename. The flow stores these in media-service and hands the user a link. HTML today;
 * a PDF renderer drops in behind the same seam without touching the flows.
 */
public record RenderedDoc(byte[] content, String mimeType, String filename) {
}
