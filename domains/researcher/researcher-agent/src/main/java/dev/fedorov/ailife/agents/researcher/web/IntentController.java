package dev.fedorov.ailife.agents.researcher.web;

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
 * Hit by the orchestrator when intent routing selects {@code researcher}. R-c ships a minimal
 * chat fallback (AGENT.md as the system prompt) so the agent boots, registers and replies
 * end-to-end. R-d replaces this with the cheap-first research flow (search → fetch → one LLM
 * synthesis on the shared {@code Coordinator}).
 */
@RestController
@RequestMapping("/agents/researcher")
public class IntentController {

    private final LlmClient llm;
    private final AgentManifest manifest;

    public IntentController(LlmClient llm, AgentManifest manifest) {
        this.llm = llm;
        this.manifest = manifest;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        String text = message.text() == null ? "" : message.text();
        LlmChatRequest req = LlmChatRequest.of(LlmChannel.DEFAULT, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(text)));
        return llm.chat(req).map(r -> new IntentResponse(
                manifest.name(), r.content() == null ? "" : r.content(), r.model()));
    }
}
