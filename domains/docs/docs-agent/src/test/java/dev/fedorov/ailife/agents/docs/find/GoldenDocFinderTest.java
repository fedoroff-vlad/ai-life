package dev.fedorov.ailife.agents.docs.find;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.docs.config.DocsAgentProperties;
import dev.fedorov.ailife.agents.docs.http.DocumentClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> — exercises the docs {@code doc-finder} <b>JSON-extract skill</b> against a
 * <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting
 * <b>structure, not text</b> (roadmap §Risks): given a "find my X" request, the real model must distil a
 * non-blank search {@code query} the production {@link DocFinder} passes to the archive search.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); see
 * {@code platform/llm-gateway/README.md} §Golden tests. {@link DocumentClient} (the search) is mocked;
 * we capture the query the production parser built from the real model's reply and assert its shape,
 * never the wording.
 */
@GoldenLlmTest
class GoldenDocFinderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final DocumentClient documents = mock(DocumentClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "docs", "docs agent", "0.1.0", 8117,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenDocFinderTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenDocFinderTest.class.getClassLoader(),
                    "skills/docs/doc-finder/SKILL.md")));
    private final DocFinder finder =
            new DocFinder(documents, GoldenLlm.client(), skills, manifest, json, new DocsAgentProperties());

    /**
     * STRUCTURE — the real model, given the real finder prompt and a "find my X" request, must distil a
     * non-blank query the production parser passes to the archive search. Checks the extract's shape,
     * never the wording.
     */
    @Test
    void distilsANonBlankSearchQuery() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        when(documents.search(any(), any(), any(), any())).thenReturn(Mono.just(List.of(new DocumentDto(
                UUID.randomUUID(), household, user, "media-1", "contract", "Договор аренды",
                "ООО Ромашка", LocalDate.of(2026, 1, 15), null, null, "договор аренды", null, Instant.now()))));

        var msg = GoldenLlm.message(household, user, "найди мой договор аренды квартиры за прошлый год");

        var resp = finder.find(msg).block(Duration.ofSeconds(120));
        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();

        // Reaching the search means the model produced a usable query.
        verify(documents, times(1)).search(eq(household), queryCaptor.capture(), any(), any());
        assertThat(queryCaptor.getValue())
                .as("model distilled no usable query (degraded reply: %s)", resp.text())
                .isNotBlank();
    }
}
