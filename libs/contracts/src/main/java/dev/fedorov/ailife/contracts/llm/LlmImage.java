package dev.fedorov.ailife.contracts.llm;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single inline image attached to an {@link LlmMessage} for the {@code vision} channel.
 * Carried as base64 so the gateway stays stateless — it never fetches a URL on the caller's
 * behalf. {@code mediaType} is an IANA type the vision model accepts (e.g. {@code image/jpeg},
 * {@code image/png}, {@code image/webp}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmImage(String mediaType, String dataBase64) {

    public LlmImage {
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        if (dataBase64 == null || dataBase64.isBlank()) {
            throw new IllegalArgumentException("dataBase64 must not be blank");
        }
    }
}
