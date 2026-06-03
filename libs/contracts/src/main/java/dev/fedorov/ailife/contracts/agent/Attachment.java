package dev.fedorov.ailife.contracts.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Attachment(
        String kind,
        String mimeType,
        String storageUri,
        String caption) {
}
