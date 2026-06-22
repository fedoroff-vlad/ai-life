package dev.fedorov.ailife.mcp.imagegen.engine;

import java.util.List;

/**
 * Pluggable image-generation backend. The default is {@link StubImageEngine} (a placeholder PNG, no
 * model, no cost); {@link LocalImageEngine} (a self-hosted GPU model server) replaces it via
 * {@code image-gen.engine=local} with no caller change. Mirrors {@code mcp-market-data}'s
 * {@code MarketDataSource} / {@code mcp-web}'s {@code SearchEngine} selector. Synchronous (the MCP
 * {@code @Tool} is blocking by convention; the {@code /internal} passthrough runs it off the event loop).
 */
public interface ImageEngine {

    /**
     * @param prompt   what to generate.
     * @param refImages reference photo bytes (empty for text-to-image; person + garment for try-on).
     * @return the generated image bytes + mime type + the model id that produced it.
     */
    GeneratedImage generate(String prompt, List<byte[]> refImages);

    record GeneratedImage(byte[] bytes, String mimeType, String model) {
    }
}
