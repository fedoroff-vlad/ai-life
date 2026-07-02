package dev.fedorov.ailife.agents.docs.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.http.MemoryClient;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.docs.http.DocumentClient;
import dev.fedorov.ailife.agents.docs.http.OcrClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.docs.SaveDocumentInput;
import dev.fedorov.ailife.contracts.media.OcrResult;
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
 * Stage 5 <b>golden test</b> — exercises the docs {@code doc-archiver} <b>JSON-extract skill</b> against
 * a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting
 * <b>structure, not text</b> (roadmap §Risks): given a real document's OCR text, the real model must
 * emit a metadata object the production {@link DocArchiver} parses into a {@link SaveDocumentInput} with
 * a usable {@code docType} plus at least one other extracted field (title / party / date).
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); see
 * {@code platform/llm-gateway/README.md} §Golden tests. {@link OcrClient} (the OCR) and
 * {@link DocumentClient} (the write) are mocked; we capture the {@link SaveDocumentInput} the production
 * parser built from the real model's reply and assert its structure, never the wording.
 */
@GoldenLlmTest
class GoldenDocArchiverTest {

    private final ObjectMapper json = new ObjectMapper();
    private final OcrClient ocr = mock(OcrClient.class);
    private final DocumentClient documents = mock(DocumentClient.class);
    private final MemoryClient memory = mock(MemoryClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "docs", "docs agent", "0.1.0", 8117,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenDocArchiverTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenDocArchiverTest.class.getClassLoader(),
                    "skills/docs/doc-archiver/SKILL.md")));
    private final DocArchiver archiver =
            new DocArchiver(ocr, documents, memory, GoldenLlm.client(), skills, manifest, json);

    /**
     * STRUCTURE — the real model, given the real archiver prompt and a concrete document's OCR text,
     * must emit a metadata object the production parser turns into a {@link SaveDocumentInput} with a
     * non-blank {@code docType} plus at least one other extracted field (title / party / date). Checks
     * the extract's shape, never the wording.
     */
    @Test
    void extractsStructuredDocumentMetadata() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String ocrText = "ГАРАНТИЙНЫЙ ТАЛОН\nХолодильник Bosch KGN39\nПродавец: М.Видео\n"
                + "Дата продажи: 12.03.2026\nСрок гарантии: 24 месяца";
        when(ocr.ocr(any())).thenReturn(Mono.just(new OcrResult(ocrText, "ru", 0.9)));
        ArgumentCaptor<SaveDocumentInput> captor = ArgumentCaptor.forClass(SaveDocumentInput.class);
        when(documents.save(any(SaveDocumentInput.class))).thenAnswer(inv -> {
            SaveDocumentInput in = inv.getArgument(0);
            return Mono.just(new DocumentDto(
                    UUID.randomUUID(), in.householdId(), in.ownerId(), in.mediaId(), in.docType(),
                    in.title(), in.party(), in.docDate(), in.amount(), in.currency(), in.ocrText(),
                    in.tags(), Instant.now()));
        });
        when(memory.remember(any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        var msg = GoldenLlm.message(household, user, "вот гарантия на холодильник, сохрани");

        var resp = archiver.archive(msg, "media-golden").block(Duration.ofSeconds(120));
        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();

        // Reaching the write means the model produced a parseable metadata object.
        verify(documents, times(1)).save(captor.capture());
        SaveDocumentInput saved = captor.getValue();
        // The full OCR text is always stored as the search corpus, regardless of the extract.
        assertThat(saved.ocrText()).contains("Bosch");
        assertThat(saved.docType())
                .as("model produced no usable docType (degraded reply: %s)", resp.text())
                .isNotBlank();
        boolean hasOtherField = (saved.title() != null && !saved.title().isBlank())
                || (saved.party() != null && !saved.party().isBlank())
                || saved.docDate() != null;
        assertThat(hasOtherField)
                .as("extract had only a docType — no title/party/date, not structured metadata: %s", saved)
                .isTrue();
    }
}
