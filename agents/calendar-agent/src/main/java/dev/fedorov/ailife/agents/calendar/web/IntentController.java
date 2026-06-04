package dev.fedorov.ailife.agents.calendar.web;

import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Hit by orchestrator when intent routing selects {@code calendar}. PR9c1 wiring:
 * the AGENT.md body is the system prompt; the LLM gateway answers on the default
 * channel. Real domain skills (birthday-greeter, datetime parsing, mcp-caldav
 * tool calls) land in PR9c2+ and slot in here before the LLM call.
 */
@RestController
@RequestMapping("/agents/calendar")
public class IntentController {

    private final LlmClient llm;
    private final AgentManifest manifest;

    public IntentController(LlmClient llm, AgentManifest manifest) {
        this.llm = llm;
        this.manifest = manifest;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        var request = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(message.text())));
        return llm.chat(request)
                .map(resp -> new IntentResponse(manifest.name(), resp.content(), resp.model()));
    }
}
