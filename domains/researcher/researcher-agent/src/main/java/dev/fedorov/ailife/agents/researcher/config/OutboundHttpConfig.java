package dev.fedorov.ailife.agents.researcher.config;

import dev.fedorov.ailife.agentruntime.http.CaptionClient;
import dev.fedorov.ailife.agentruntime.http.WebSearchClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The agent-specific outbound {@link WebClient}, {@code clone()}d off the shared builder to avoid
 * base-URL leakage (same pattern as the other agents): {@code mcpWeb} backs the research flow (R-d);
 * {@code mcpMediaFetch} + {@code mcpMediaProcessing} back the video-understanding flow (V-c). The shared
 * {@code profile/notifier/memory} WebClients the runtime clients bind on live in {@code agent-runtime}'s
 * {@code AgentRuntimeConfig} (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpWebWebClient(WebClient.Builder builder, ResearcherAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpWebUrl()).build();
    }

    /** Shared {@code mcp-web} search client (agent-runtime) over this agent's web WebClient. */
    @Bean
    public WebSearchClient webSearchClient(@Qualifier("mcpWebWebClient") WebClient mcpWebWebClient) {
        return new WebSearchClient(mcpWebWebClient);
    }

    @Bean
    public WebClient mcpMediaFetchWebClient(WebClient.Builder builder, ResearcherAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMediaFetchUrl()).build();
    }

    @Bean
    public WebClient mcpMediaProcessingWebClient(WebClient.Builder builder,
                                                 ResearcherAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMediaProcessingUrl()).build();
    }

    /**
     * Shared {@code mcp-media-processing} vision-caption client (agent-runtime) — the visual tier of the
     * video flow captions each keyframe through it (reused, not re-embedded).
     */
    @Bean
    public CaptionClient captionClient(
            @Qualifier("mcpMediaProcessingWebClient") WebClient mcpMediaProcessingWebClient) {
        return new CaptionClient(mcpMediaProcessingWebClient);
    }
}
