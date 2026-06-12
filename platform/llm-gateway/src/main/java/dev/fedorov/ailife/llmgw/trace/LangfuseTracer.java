package dev.fedorov.ailife.llmgw.trace;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.contracts.llm.LlmUsage;
import dev.fedorov.ailife.llmgw.config.LangfuseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exports each completed LLM call to Langfuse as a trace + a GENERATION observation, via the batch
 * ingestion API ({@code POST /api/public/ingestion}, HTTP Basic public/secret key). Covers all three
 * surfaces: non-streaming chat ({@link #traceChat}), SSE streaming chat ({@link #traceChatStream}),
 * and embeddings ({@link #traceEmbed}).
 *
 * <p>Best-effort by contract: every {@code trace*} method is called fire-and-forget from a controller
 * and never blocks the response. When {@code langfuse.enabled=false} they short-circuit to
 * {@link Mono#empty()} and make no HTTP call, so {@code mock} dev runs and tests stay silent. Any
 * ingestion failure (network, 4xx/5xx, timeout) is logged at DEBUG and swallowed — a tracing outage
 * must not surface to callers.
 *
 * <p>Streaming reports no token usage (providers emit text deltas only), so the streamed generation
 * carries the accumulated output but omits the {@code usage} block.
 */
@Component
public class LangfuseTracer {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTracer.class);
    private static final String INGESTION_PATH = "/api/public/ingestion";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final LangfuseProperties props;
    private final WebClient http;

    public LangfuseTracer(LangfuseProperties props, WebClient.Builder builder) {
        this.props = props;
        if (props.enabled()) {
            this.http = builder.clone()
                    .baseUrl(props.baseUrl())
                    .defaultHeaders(h -> h.setBasicAuth(
                            props.publicKey() == null ? "" : props.publicKey(),
                            props.secretKey() == null ? "" : props.secretKey()))
                    .build();
        } else {
            this.http = null;
        }
    }

    /** Trace a finished non-streaming {@code /v1/chat} call (model + usage come from the response). */
    public Mono<Void> traceChat(LlmChatRequest request, LlmChatResponse response,
                                Instant start, Instant end) {
        if (disabled()) {
            return Mono.empty();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        if (response.finishReason() != null) {
            meta.put("finishReason", response.finishReason());
        }
        return post(buildBatch("llm-gateway.chat", "chat", channelOf(request.channel()),
                response.model(), renderInput(request.messages()), response.content(),
                renderUsage(response.usage()), meta, start, end));
    }

    /**
     * Trace a finished streaming {@code /v1/chat/stream} call. The provider stream yields text deltas
     * only — the caller accumulates them into {@code output} and resolves the {@code model} from the
     * channel; no token usage is available, so the generation omits the usage block.
     */
    public Mono<Void> traceChatStream(LlmChatRequest request, String output, String model,
                                      Instant start, Instant end) {
        if (disabled()) {
            return Mono.empty();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("streamed", true);
        return post(buildBatch("llm-gateway.chat.stream", "chat-stream", channelOf(request.channel()),
                model, renderInput(request.messages()), output, null, meta, start, end));
    }

    /** Trace a finished {@code /v1/embed} call (always the {@code embedding} channel). */
    public Mono<Void> traceEmbed(LlmEmbedRequest request, LlmEmbedResponse response,
                                 Instant start, Instant end) {
        if (disabled()) {
            return Mono.empty();
        }
        int dim = response.vectors().isEmpty() ? 0 : response.vectors().get(0).length;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("vectorCount", response.vectors().size());
        meta.put("dimensions", dim);
        String output = response.vectors().size() + " vectors × " + dim + " dims";
        return post(buildBatch("llm-gateway.embed", "embed", "embedding",
                response.model(), request.inputs(), output,
                renderUsage(response.usage()), meta, start, end));
    }

    private boolean disabled() {
        return !props.enabled() || http == null;
    }

    private Mono<Void> post(Map<String, Object> batch) {
        return http.post()
                .uri(INGESTION_PATH)
                .bodyValue(batch)
                .retrieve()
                .toBodilessEntity()
                .timeout(TIMEOUT)
                .doOnError(e -> log.debug("Langfuse ingestion failed (swallowed): {}", e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /**
     * Build a one-call ingestion batch: a {@code trace-create} wrapping a {@code generation-create}
     * observation. {@code usage} is omitted when null (e.g. streaming).
     */
    private Map<String, Object> buildBatch(String traceName, String generationName, String channel,
                                           String model, Object input, Object output,
                                           Map<String, Object> usage, Map<String, Object> extraMeta,
                                           Instant start, Instant end) {
        String traceId = UUID.randomUUID().toString();

        Map<String, Object> traceBody = new LinkedHashMap<>();
        traceBody.put("id", traceId);
        traceBody.put("name", traceName);
        traceBody.put("timestamp", start.toString());
        traceBody.put("input", input);
        traceBody.put("output", output);
        traceBody.put("metadata", Map.of("channel", channel));

        Map<String, Object> genMeta = new LinkedHashMap<>();
        genMeta.put("channel", channel);
        genMeta.putAll(extraMeta);

        Map<String, Object> genBody = new LinkedHashMap<>();
        genBody.put("id", UUID.randomUUID().toString());
        genBody.put("traceId", traceId);
        genBody.put("type", "GENERATION");
        genBody.put("name", generationName);
        genBody.put("model", model);
        genBody.put("startTime", start.toString());
        genBody.put("endTime", end.toString());
        genBody.put("input", input);
        genBody.put("output", output);
        if (usage != null) {
            genBody.put("usage", usage);
        }
        genBody.put("metadata", genMeta);

        return Map.of("batch", List.of(
                event("trace-create", start, traceBody),
                event("generation-create", end, genBody)));
    }

    private static Map<String, Object> event(String type, Instant ts, Map<String, Object> body) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", UUID.randomUUID().toString());
        ev.put("type", type);
        ev.put("timestamp", ts.toString());
        ev.put("body", body);
        return ev;
    }

    private static String channelOf(LlmChannel channel) {
        return channel == null ? "default" : channel.name().toLowerCase();
    }

    /** Messages as a plain role/content list — Langfuse renders this as the chat input. */
    private static List<Map<String, Object>> renderInput(List<LlmMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LlmMessage m : messages) {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("role", m.role() == null ? "user" : m.role().name().toLowerCase());
            turn.put("content", m.content());
            if (m.images() != null && !m.images().isEmpty()) {
                turn.put("images", m.images().size());
            }
            out.add(turn);
        }
        return out;
    }

    /** Langfuse's usage shape: {@code input}/{@code output}/{@code total} token counts. */
    private static Map<String, Object> renderUsage(LlmUsage usage) {
        LlmUsage u = usage == null ? LlmUsage.zero() : usage;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("input", u.promptTokens());
        map.put("output", u.completionTokens());
        map.put("total", u.totalTokens());
        map.put("unit", "TOKENS");
        return map;
    }
}
