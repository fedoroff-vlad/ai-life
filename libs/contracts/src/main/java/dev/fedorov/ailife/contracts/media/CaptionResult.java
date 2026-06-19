package dev.fedorov.ailife.contracts.media;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Free-text result of the {@code mcp-media-processing} capability's {@code caption} tool:
 * what an LLM vision model said about a stored image, given the caller's instruction.
 * {@code text} is the model's answer (a description, or structured text the caller asked
 * for — e.g. JSON it then parses); {@code model} is the resolved vision model, informational.
 * Like {@code ocr}, the capability returns text only — interpreting it is the caller's skill.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptionResult(
        String text,
        String model) {
}
