package dev.fedorov.ailife.llmgw.web;

import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmEmbedRequest;
import dev.fedorov.ailife.contracts.llm.LlmEmbedResponse;
import dev.fedorov.ailife.llmgw.provider.ProviderRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/embed")
public class EmbedController {

    private final ProviderRegistry providers;

    public EmbedController(ProviderRegistry providers) {
        this.providers = providers;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LlmEmbedResponse> embed(@RequestBody LlmEmbedRequest request) {
        return providers.active().embed(LlmChannel.EMBEDDING, request);
    }
}
