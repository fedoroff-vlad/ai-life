package dev.fedorov.ailife.agents.researcher.web;

import dev.fedorov.ailife.agents.researcher.flow.Researcher;
import dev.fedorov.ailife.agents.researcher.flow.VideoUnderstanding;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Hit by the orchestrator when intent routing selects {@code researcher}. The agent is a cross-domain
 * specialist: a routed message is either a <b>specific video to understand</b> (a video-file attachment
 * or a video-host link — {@link VideoUnderstanding#detect}) → the multimodal {@link VideoUnderstanding}
 * flow (V-c), or otherwise a research topic → the cheap-first {@link Researcher} flow (search → fetch →
 * one LLM synthesis). Both return one grounded answer.
 */
@RestController
@RequestMapping("/agents/researcher")
public class IntentController {

    private final Researcher researcher;
    private final VideoUnderstanding videoUnderstanding;
    private final AgentManifest manifest;

    public IntentController(Researcher researcher, VideoUnderstanding videoUnderstanding,
                           AgentManifest manifest) {
        this.researcher = researcher;
        this.videoUnderstanding = videoUnderstanding;
        this.manifest = manifest;
    }

    @PostMapping("/intent")
    public Mono<IntentResponse> intent(@RequestBody NormalizedMessage message) {
        if (VideoUnderstanding.detect(message).isPresent()) {
            return videoUnderstanding.understand(message)
                    .map(r -> new IntentResponse(manifest.name(), r.text(), r.model()));
        }
        return researcher.research(message)
                .map(r -> new IntentResponse(manifest.name(), r.text(), r.model()));
    }
}
