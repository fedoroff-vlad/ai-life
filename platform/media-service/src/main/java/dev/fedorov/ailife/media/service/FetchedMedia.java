package dev.fedorov.ailife.media.service;

/** Raw bytes of a stored object plus the MIME type to serve them with. */
public record FetchedMedia(String mimeType, byte[] bytes) {
}
