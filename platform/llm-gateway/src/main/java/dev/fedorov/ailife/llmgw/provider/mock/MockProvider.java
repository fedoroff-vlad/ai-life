package dev.fedorov.ailife.llmgw.provider.mock;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.llm.LlmRole;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llmgw.config.LlmGatewayProperties;
import dev.fedorov.ailife.llmgw.provider.LlmProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Deterministic stub used during development and in golden tests for skills.
 * <p>
 * Output rules:
 * <ul>
 *   <li>Chat: echoes the last USER message prefixed with the channel name in square brackets.</li>
 *   <li>Stream: splits the same content on whitespace and emits word-by-word deltas.</li>
 *   <li>Embeddings: produces a 384-dim vector seeded from a CRC32 of the input — same input
 *       → same vector. Stable but not semantically meaningful.</li>
 * </ul>
 * Token counts are approximate (chars / 4) so usage looks realistic in tests.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockProvider implements LlmProvider {

    private static final int EMBEDDING_DIM = 384;

    private final LlmGatewayProperties props;

    public MockProvider(LlmGatewayProperties props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public Mono<LlmChatResponse> chat(LlmChatRequest request) {
        String content = synthesise(request);
        String model = props.channelModels().get(request.channel());
        LlmUsage usage = approximateUsage(request, content);
        return Mono.just(new LlmChatResponse(model, content, "stop", usage));
    }

    @Override
    public Flux<String> chatStream(LlmChatRequest request) {
        String content = synthesise(request);
        String[] tokens = content.split("(?<=\\s)");
        return Flux.fromArray(tokens);
    }

    @Override
    public Mono<LlmEmbedResponse> embed(LlmChannel channel, LlmEmbedRequest request) {
        List<float[]> vectors = new ArrayList<>(request.inputs().size());
        for (String input : request.inputs()) {
            vectors.add(deterministicVector(input));
        }
        int promptTokens = request.inputs().stream().mapToInt(s -> s.length() / 4 + 1).sum();
        return Mono.just(new LlmEmbedResponse(
                props.channelModels().get(LlmChannel.EMBEDDING),
                vectors,
                new LlmUsage(promptTokens, 0, promptTokens)));
    }

    private String synthesise(LlmChatRequest request) {
        LlmMessage lastUser = request.messages().stream()
                .filter(m -> m.role() == LlmRole.USER)
                .reduce((a, b) -> b)
                .orElse(null);
        String text = lastUser == null || lastUser.content() == null ? "" : lastUser.content();
        // Surface attached images deterministically so vision callers can assert on the echo.
        String images = lastUser != null && lastUser.hasImages()
                ? " [images=" + lastUser.images().size() + "]"
                : "";
        return "[" + request.channel().wire() + "] " + text + images;
    }

    private LlmUsage approximateUsage(LlmChatRequest request, String content) {
        int prompt = request.messages().stream()
                .mapToInt(m -> m.content() == null ? 0 : m.content().length() / 4 + 1)
                .sum();
        int completion = content.length() / 4 + 1;
        return new LlmUsage(prompt, completion, prompt + completion);
    }

    private float[] deterministicVector(String input) {
        CRC32 crc = new CRC32();
        crc.update(input.getBytes(StandardCharsets.UTF_8));
        long seed = crc.getValue();
        float[] v = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            v[i] = ((seed >>> 33) & 0xFFFF) / 65535.0f - 0.5f;
        }
        return v;
    }
}
