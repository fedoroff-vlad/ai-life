package dev.fedorov.ailife.agents.tasks.web;

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
 * Hit by orchestrator when intent routing selects {@code tasks}. Skeleton slice: a plain LLM
 * chat with the AGENT.md body as the system prompt and the user's message as the turn, on the
 * {@code fast} channel. MCP-tool-call routing (capture/clarify/list via mcp-tasks) lands in a
 * later PR — mirrors how finance-agent's IntentController grew from this shape into PR35's
 * tool router.
 */
@RestController
@RequestMapping("/agents/tasks")
public class IntentController {

    private final LlmClient llm;
    private final AgentManifest manifest;

    public IntentController(LlmClient llm, AgentManifest manifest) {
        this.llm = llm;
        this.manifest = manifest;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        LlmChatRequest chat = LlmChatRequest.of(LlmChannel.FAST, List.of(
                LlmMessage.system(manifest.body()),
                LlmMessage.user(message.text())));
        return llm.chat(chat)
                .map(resp -> new IntentResponse(manifest.name(), resp.content(), resp.model()));
    }
}
