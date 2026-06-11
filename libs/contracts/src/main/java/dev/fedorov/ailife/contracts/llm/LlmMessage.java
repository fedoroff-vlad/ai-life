package dev.fedorov.ailife.contracts.llm;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record LlmMessage(
        LlmRole role,
        String content,
        @JsonInclude(JsonInclude.Include.NON_NULL) String name,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<LlmImage> images) {

    public LlmMessage {
        // Normalise empty → null so text-only messages serialise without an "images" key
        // and so providers can branch on hasImages() without a size check.
        images = (images == null || images.isEmpty()) ? null : List.copyOf(images);
    }

    /** Back-compat constructor for the text-only callers that predate the vision channel. */
    public LlmMessage(LlmRole role, String content, String name) {
        this(role, content, name, null);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(LlmRole.SYSTEM, content, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(LlmRole.USER, content, null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(LlmRole.ASSISTANT, content, null);
    }

    /** A USER turn carrying one or more inline images alongside optional text (vision channel). */
    public static LlmMessage userWithImages(String content, List<LlmImage> images) {
        return new LlmMessage(LlmRole.USER, content, null, images);
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
}
