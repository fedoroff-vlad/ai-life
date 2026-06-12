package dev.fedorov.ailife.llmgw.trace;

import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
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
 * Exports each completed chat call to Langfuse as a trace + a GENERATION observation, via the
 * batch ingestion API ({@code POST /api/public/ingestion}, HTTP Basic public/secret key).
 *
 * <p>Best-effort by contract: {@link #traceChat} is called fire-and-forget from the controller and
 * never blocks the LLM response. When {@code langfuse.enabled=false} it short-circuits to
 * {@link Mono#empty()} and makes no HTTP call, so {@code mock} dev runs and tests stay silent. Any
 * ingestion failure (network, 4xx/5xx, timeout) is logged at DEBUG and swallowed — a tracing outage
 * must not surface to callers.
 *
 * <p>Only the non-streaming {@code /v1/chat} path is traced here; SSE streaming and embeddings are a
 * separate follow-up (they need delta accumulation / a different usage shape).
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

    /**
     * Build and POST one ingestion batch for a finished chat call. Returns the in-flight POST so the
     * caller can {@code .subscribe()} it fire-and-forget (or {@code .block()} it in a test); a
     * no-op {@link Mono#empty()} when tracing is disabled.
     */
    public Mono<Void> traceChat(LlmChatRequest request, LlmChatResponse response,
                                Instant start, Instant end) {
        if (!props.enabled() || http == null) {
            return Mono.empty();
        }
        Map<String, Object> batch = buildBatch(request, response, start, end);
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

    private Map<String, Object> buildBatch(LlmChatRequest request, LlmChatResponse response,
                                           Instant start, Instant end) {
        String traceId = UUID.randomUUID().toString();
        String channel = request.channel() == null ? "default"
                : request.channel().name().toLowerCase();

        Map<String, Object> traceBody = new LinkedHashMap<>();
        traceBody.put("id", traceId);
        traceBody.put("name", "llm-gateway.chat");
        traceBody.put("timestamp", start.toString());
        traceBody.put("input", renderInput(request.messages()));
        traceBody.put("output", response.content());
        traceBody.put("metadata", Map.of("channel", channel));

        Map<String, Object> genBody = new LinkedHashMap<>();
        genBody.put("id", UUID.randomUUID().toString());
        genBody.put("traceId", traceId);
        genBody.put("type", "GENERATION");
        genBody.put("name", "chat");
        genBody.put("model", response.model());
        genBody.put("startTime", start.toString());
        genBody.put("endTime", end.toString());
        genBody.put("input", renderInput(request.messages()));
        genBody.put("output", response.content());
        genBody.put("usage", renderUsage(response.usage()));
        Map<String, Object> genMeta = new LinkedHashMap<>();
        genMeta.put("channel", channel);
        if (response.finishReason() != null) {
            genMeta.put("finishReason", response.finishReason());
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
