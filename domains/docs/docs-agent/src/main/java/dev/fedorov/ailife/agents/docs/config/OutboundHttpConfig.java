package dev.fedorov.ailife.agents.docs.config;

import dev.fedorov.ailife.sharing.DefaultSharingPolicy;
import dev.fedorov.ailife.sharing.LearnedSharingPolicy;
import dev.fedorov.ailife.sharing.ProfileSharingClient;
import dev.fedorov.ailife.sharing.SharingLearningClient;
import dev.fedorov.ailife.sharing.SharingResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * One {@link WebClient} per agent-specific outbound dependency, each {@code clone()}d off the shared
 * builder to avoid base-URL leakage (same pattern as the other agents): {@code mcpDocs} (its data —
 * store + search) + {@code mcpMediaProcessing} (OCR of a document photo). The shared
 * {@code profile/notifier/memory} WebClients live in {@code agent-runtime}'s {@code AgentRuntimeConfig}
 * (built from {@code SharedClientProperties}).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public WebClient mcpDocsWebClient(WebClient.Builder builder, DocsAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpDocsUrl()).build();
    }

    @Bean
    public WebClient mcpMediaProcessingWebClient(WebClient.Builder builder, DocsAgentProperties props) {
        return builder.clone().baseUrl(props.getMcpMediaProcessingUrl()).build();
    }

    /**
     * The sharing capability's identity read (ADR-0002 slice 7), over the shared
     * {@code profileServiceWebClient} (built by agent-runtime from {@code SharedClientProperties}) — the
     * same client backing {@link dev.fedorov.ailife.agentruntime.http.ProfileClient}. Used by the archive
     * write path to route a document to the member's personal ∪ shared household; mirrors the other agents.
     */
    @Bean
    public ProfileSharingClient profileSharingClient(
            @Qualifier("profileServiceWebClient") WebClient profileServiceWebClient) {
        return new ProfileSharingClient(profileServiceWebClient);
    }

    /**
     * The learned-decision tally read/write (ADR-0002 item 8), over the shared {@code memoryServiceWebClient}
     * (built by agent-runtime from {@code SharedClientProperties}). Best-effort — never delays or fails a
     * document archive; mirrors calendar / finance / tasks / nutrition wiring.
     */
    @Bean
    public SharingLearningClient sharingLearningClient(
            @Qualifier("memoryServiceWebClient") WebClient memoryServiceWebClient) {
        return new SharingLearningClient(memoryServiceWebClient);
    }

    /**
     * The sharing capability's <b>write-path</b> engine (ADR-0002 slice 7), wired with docs'
     * {@link dev.fedorov.ailife.agents.docs.sharing.DocsSharingPolicy} (injected as the
     * {@link DefaultSharingPolicy}). {@code DocArchiver} routes a saved document against the acting user's
     * household-routing split to a concrete personal/shared {@code household_id} (warranty/contract →
     * shared, else private). Item 8: the static policy is wrapped in a {@link LearnedSharingPolicy} and the
     * resolver uses its learning-enabled constructor, so a document with no explicit household/personal
     * signal defaults to what the owner has repeatedly chosen for the same signal profile once the tally is
     * deep + decisive, and to the static doc-type rule otherwise; explicit choices are recorded. Both
     * best-effort; the routing mechanism is unchanged. Mirrors nutrition.
     */
    @Bean
    public SharingResolver sharingResolver(ProfileSharingClient profileSharingClient,
                                           DefaultSharingPolicy defaultSharingPolicy,
                                           SharingLearningClient sharingLearningClient) {
        DefaultSharingPolicy learned =
                new LearnedSharingPolicy(defaultSharingPolicy, sharingLearningClient, "docs");
        return new SharingResolver(profileSharingClient, learned, sharingLearningClient, "docs");
    }
}
