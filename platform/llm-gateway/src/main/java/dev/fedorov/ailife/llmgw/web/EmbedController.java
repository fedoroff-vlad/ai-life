package dev.fedorov.ailife.llmgw.web;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.llmgw.provider.ProviderRegistry;
import dev.fedorov.ailife.llmgw.trace.LangfuseTracer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;

@RestController
@RequestMapping("/v1/embed")
public class EmbedController {

    private final ProviderRegistry providers;
    private final LangfuseTracer tracer;

    public EmbedController(ProviderRegistry providers, LangfuseTracer tracer) {
        this.providers = providers;
        this.tracer = tracer;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LlmEmbedResponse> embed(@RequestBody LlmEmbedRequest request) {
        Instant start = Instant.now();
        return providers.active().embed(LlmChannel.EMBEDDING, request)
                // Fire-and-forget trace: never delays or breaks the response (no-op when disabled).
                .doOnNext(resp -> tracer.traceEmbed(request, resp, start, Instant.now()).subscribe());
    }
}
