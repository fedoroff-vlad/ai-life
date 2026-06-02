package dev.fedorov.ailife.llmgw.web;

import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmChatResponse;
import dev.fedorov.ailife.llmgw.provider.ProviderRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/chat")
public class ChatController {

    private final ProviderRegistry providers;

    public ChatController(ProviderRegistry providers) {
        this.providers = providers;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<LlmChatResponse> chat(@RequestBody LlmChatRequest request) {
        return providers.active().chat(request);
    }

    @PostMapping(path = "/stream",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody LlmChatRequest request) {
        return providers.active().chatStream(request);
    }
}
