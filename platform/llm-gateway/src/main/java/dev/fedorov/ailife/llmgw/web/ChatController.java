package dev.fedorov.ailife.llmgw.web;

import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.llmgw.config.LlmGatewayProperties;
import dev.fedorov.ailife.llmgw.provider.ProviderRegistry;
import dev.fedorov.ailife.llmgw.trace.LangfuseTracer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@RestController
@RequestMapping("/v1/chat")
public class ChatController {

    private final ProviderRegistry providers;
    private final LangfuseTracer tracer;
    private final LlmGatewayProperties props;

    public ChatController(ProviderRegistry providers, LangfuseTracer tracer,
                          LlmGatewayProperties props) {
        this.providers = providers;
        this.tracer = tracer;
        this.props = props;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LlmChatResponse> chat(@RequestBody LlmChatRequest request) {
        Instant start = Instant.now();
        return providers.active().chat(request)
                // Fire-and-forget trace: never delays or breaks the response (no-op when disabled).
                .doOnNext(resp -> tracer.traceChat(request, resp, start, Instant.now()).subscribe());
    }

    @PostMapping(path = "/stream",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody LlmChatRequest request) {
        Instant start = Instant.now();
        // The stream yields text deltas only — accumulate them and resolve the model from the channel
        // so the trace at completion carries the full output. Fire-and-forget, no-op when disabled.
        StringBuilder acc = new StringBuilder();
        String model = props.channelModels().get(request.channel());
        return providers.active().chatStream(request)
                .doOnNext(acc::append)
                .doOnComplete(() -> tracer
                        .traceChatStream(request, acc.toString(), model, start, Instant.now())
                        .subscribe());
    }
}
