package dev.fedorov.ailife.agents.notes.list;

import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.notes.http.NoteClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.note.NoteDto;
import dev.fedorov.ailife.contracts.note.WriteNoteRequest;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden test — exercises the {@code list-manager} <b>op-classify skill</b> against a <b>real model</b>
 * (local Ollama via a running llm-gateway), asserting <b>structure, not text</b>: given a natural
 * "добавь … в список …" message, the real model must emit an {@code {op, list, item}} the production
 * {@link ListManager} parses into an {@code add} that reaches the note write with a non-empty checklist
 * item. Opt-in / gated via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); {@link NoteClient} is mocked.
 */
@GoldenLlmTest
class GoldenListManagerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final NoteClient notes = mock(NoteClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "notes", "notes agent", "0.1.0", 8118,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenListManagerTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenListManagerTest.class.getClassLoader(),
                    "skills/knowledge/list-manager/SKILL.md")));
    private final ListManager manager =
            new ListManager(notes, GoldenLlm.client(), skills, manifest, json);

    @Test
    void classifiesAnAddIntoAChecklistWrite() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        ArgumentCaptor<WriteNoteRequest> captor = ArgumentCaptor.forClass(WriteNoteRequest.class);
        when(notes.list(any(), anyInt())).thenReturn(Mono.just(List.of()));   // no list yet → create path
        when(notes.create(any(WriteNoteRequest.class))).thenAnswer(inv -> {
            WriteNoteRequest in = inv.getArgument(0);
            return Mono.just(new NoteDto(UUID.randomUUID(), in.householdId(), in.ownerId(), in.title(),
                    in.type(), in.tags(), in.source(), in.personId(), in.bodyMd(), in.frontmatter(),
                    Instant.now(), Instant.now()));
        });

        var msg = GoldenLlm.message(household, user, "добавь молоко в список покупок");

        var resp = manager.handle(msg).block(Duration.ofSeconds(120));
        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();

        // Reaching create means the model produced a parseable {op:add, item:…}.
        verify(notes, times(1)).create(captor.capture());
        WriteNoteRequest saved = captor.getValue();
        assertThat(saved.type()).isEqualTo("list");
        assertThat(saved.bodyMd())
                .as("model produced no usable item (degraded reply: %s)", resp.text())
                .contains("- [ ]");
    }
}
