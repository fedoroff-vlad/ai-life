package dev.fedorov.ailife.agents.notes.find;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
 * Stage 5 <b>golden test</b> — exercises the {@code note-finder} <b>JSON-extract skill</b> against a
 * <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting
 * <b>structure, not text</b>: given a "что я думал про …" request, the real model must distil a
 * non-blank search {@code query} the production {@link NoteFinder} passes to memory-service recall.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}). {@link MemoryClient} (the
 * recall) and {@link NoteClient} are mocked; we capture the query the production parser built from the
 * real model's reply and assert its shape, never the wording.
 */
@GoldenLlmTest
class GoldenNoteFinderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final NoteClient notes = mock(NoteClient.class);
    private final MemoryClient memory = mock(MemoryClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "notes", "notes agent", "0.1.0", 8118,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenNoteFinderTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenNoteFinderTest.class.getClassLoader(),
                    "skills/knowledge/note-finder/SKILL.md")));
    private final NoteFinder finder =
            new NoteFinder(notes, memory, GoldenLlm.client(), skills, manifest, json);

    @Test
    void distilsANonBlankSearchQuery() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        // Empty recall short-circuits to "nothing found"; reaching recall proves the query was distilled.
        when(memory.recall(any(), any(), any(), any())).thenReturn(Mono.just(List.of()));

        var msg = GoldenLlm.message(household, user, "что я думал про подарок маме на день рождения?");

        var resp = finder.find(msg).block(Duration.ofSeconds(120));
        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();

        verify(memory, times(1)).recall(eq(household), any(), any(), queryCaptor.capture());
        assertThat(queryCaptor.getValue())
                .as("model distilled no usable query (degraded reply: %s)", resp.text())
                .isNotBlank();
    }
}
