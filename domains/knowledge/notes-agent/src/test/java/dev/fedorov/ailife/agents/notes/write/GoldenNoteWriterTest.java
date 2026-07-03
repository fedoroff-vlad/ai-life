package dev.fedorov.ailife.agents.notes.write;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> — exercises the {@code note-writer} <b>JSON-extract skill</b> against a
 * <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting
 * <b>structure, not text</b>: given a "запомни …" request, the real model must emit a note object the
 * production {@link NoteWriter} parses into a {@link WriteNoteRequest} with a non-blank title + body.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}). {@link NoteClient} (the write)
 * is mocked; we capture the {@link WriteNoteRequest} the production parser built from the real model's
 * reply and assert its shape, never the wording.
 */
@GoldenLlmTest
class GoldenNoteWriterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final NoteClient notes = mock(NoteClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "notes", "notes agent", "0.1.0", 8118,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenNoteWriterTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenNoteWriterTest.class.getClassLoader(),
                    "skills/knowledge/note-writer/SKILL.md")));
    private final NoteWriter writer =
            new NoteWriter(notes, GoldenLlm.client(), skills, manifest, json);

    @Test
    void structuresANoteWithTitleAndBody() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        ArgumentCaptor<WriteNoteRequest> captor = ArgumentCaptor.forClass(WriteNoteRequest.class);
        when(notes.create(any(WriteNoteRequest.class))).thenAnswer(inv -> {
            WriteNoteRequest in = inv.getArgument(0);
            return Mono.just(new NoteDto(UUID.randomUUID(), in.householdId(), in.ownerId(), in.title(),
                    in.type(), in.tags(), in.source(), in.personId(), in.bodyMd(), in.frontmatter(),
                    Instant.now(), Instant.now()));
        });

        var msg = GoldenLlm.message(household, user,
                "запомни, что мама любит пионы в горшке, а не срезку");

        var resp = writer.capture(msg).block(Duration.ofSeconds(120));
        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();

        // Reaching the write means the model produced a parseable note object.
        verify(notes, times(1)).create(captor.capture());
        WriteNoteRequest saved = captor.getValue();
        assertThat(saved.title())
                .as("model produced no usable title (degraded reply: %s)", resp.text())
                .isNotBlank();
        assertThat(saved.bodyMd())
                .as("note has no body (degraded reply: %s)", resp.text())
                .isNotBlank();
    }
}
