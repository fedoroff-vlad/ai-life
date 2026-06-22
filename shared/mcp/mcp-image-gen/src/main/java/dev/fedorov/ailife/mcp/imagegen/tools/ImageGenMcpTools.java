package dev.fedorov.ailife.mcp.imagegen.tools;

import dev.fedorov.ailife.contracts.imagegen.ImageGenInput;
import dev.fedorov.ailife.contracts.imagegen.ImageGenResult;
import dev.fedorov.ailife.contracts.media.MediaObjectDto;
import dev.fedorov.ailife.mcp.imagegen.engine.ImageEngine;
import dev.fedorov.ailife.mcp.imagegen.media.MediaUploader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The shared image-generation toolbox. {@code generate_image} renders an image from a prompt via the
 * configured {@link ImageEngine} (stub now, a local GPU model later — config swap, no caller change),
 * stores it in media-service, and returns the {@link ImageGenResult} (the media id + the engine).
 * Any agent binds this over MCP/SSE; the deterministic path goes through {@code /internal/generate}.
 *
 * <p>Reference photos (try-on) are carried by the contract but not resolved yet — the stub is
 * prompt-only; fetching the person/garment bytes lands when a real try-on engine is wired.
 */
@Component
public class ImageGenMcpTools {

    private final ImageEngine engine;
    private final MediaUploader media;

    public ImageGenMcpTools(ImageEngine engine, MediaUploader media) {
        this.engine = engine;
        this.media = media;
    }

    @Tool(description = """
            Generate an image from a text prompt and store it in media-service; returns the stored
            media id (embed it like any other media object) plus the engine that produced it. Pass
            `householdId` (and optional `ownerId`) to scope the stored object, the `prompt`, and
            optional `refMediaIds` (reference photos for a future virtual try-on). The backend engine
            is configured server-side (a placeholder now; a real model later) — the caller is engine-agnostic.
            """)
    public ImageGenResult generateImage(ImageGenInput input) {
        if (input == null || input.householdId() == null) {
            throw new IllegalArgumentException("Missing required field: householdId");
        }
        ImageEngine.GeneratedImage img = engine.generate(input.prompt(), List.of());
        MediaObjectDto stored = media.upload(
                input.householdId(), input.ownerId(), img.mimeType(), img.bytes()).block();
        return new ImageGenResult(stored == null ? null : stored.id(), img.model());
    }
}
