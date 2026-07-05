package dev.fedorov.ailife.mcp.imagegen.engine;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

/**
 * Self-hosted {@link ImageEngine}: posts the prompt to a local model server (e.g. a Flux/SDXL or
 * try-on model running on the owner's Mac Studio) and returns the image bytes. Selected by
 * {@code image-gen.engine=local}. This is the seam the owner flips to once the GPU model is up —
 * the request shape ({@code POST /generate} with {@code {prompt}} → image bytes) is a placeholder to
 * adapt to the chosen server's API (ComfyUI / Automatic1111 / a try-on endpoint). Not exercised by
 * tests (the stub is the default); it exists so going live is a config change, not new code.
 */
@Component
@ConditionalOnProperty(name = "image-gen.engine", havingValue = "local")
public class LocalImageEngine implements ImageEngine {

    private final WebClient http;
    private final ObjectMapper json;

    public LocalImageEngine(@Qualifier("localModelWebClient") WebClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public GeneratedImage generate(String prompt, List<byte[]> refImages) {
        ObjectNode body = json.createObjectNode();
        body.put("prompt", prompt == null ? "" : prompt);
        byte[] bytes = http.post()
                .uri("/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofMinutes(2))
                .block();
        return new GeneratedImage(bytes, "image/png", "local");
    }
}
