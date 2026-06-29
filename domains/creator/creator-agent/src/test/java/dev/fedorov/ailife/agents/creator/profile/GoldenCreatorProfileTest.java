package dev.fedorov.ailife.agents.creator.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.creator.http.CreatorProfileClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.creator.CreatorProfileDto;
import dev.fedorov.ailife.contracts.creator.SetCreatorProfileInput;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
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
 * Stage 5 <b>golden test</b> (#199) — exercises the creator {@code creator-profiler} <b>JSON-extract
 * skill</b> against a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway),
 * asserting <b>structure, not text</b> (roadmap §Risks). The sixth agent on the JSON-extract surface:
 * given a typed description of a creator track, the real model must emit a profile object the production
 * {@link CreatorProfiler} parses into a {@link SetCreatorProfileInput} — a real niche plus at least one
 * other extracted field, not prose.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); see
 * {@code platform/llm-gateway/README.md} §Golden tests. {@link CreatorProfileClient} (the write) is
 * mocked; we capture the {@link SetCreatorProfileInput} the production parser built from the real
 * model's reply and assert its structure, never the wording.
 */
@GoldenLlmTest
class GoldenCreatorProfileTest {

    private final ObjectMapper json = new ObjectMapper();
    private final CreatorProfileClient profiles = mock(CreatorProfileClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "creator", "creator agent", "0.1.0", 8109,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenCreatorProfileTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenCreatorProfileTest.class.getClassLoader(), "skills/creator/creator-profiler/SKILL.md")));
    private final CreatorProfiler profiler =
            new CreatorProfiler(profiles, GoldenLlm.client(), skills, manifest, json);

    /**
     * STRUCTURE — the real model, given the real profiler prompt and a concrete track description, must
     * emit a profile object the production parser turns into a {@link SetCreatorProfileInput} with a
     * non-blank niche plus at least one other extracted field (audience or tone). This is the "parseable,
     * contract-shaped output" assertion — it checks the extract's shape, never the wording.
     */
    @Test
    void extractsAStructuredCreatorTrack() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        ArgumentCaptor<SetCreatorProfileInput> captor = ArgumentCaptor.forClass(SetCreatorProfileInput.class);
        when(profiles.set(any(SetCreatorProfileInput.class))).thenAnswer(inv -> {
            SetCreatorProfileInput in = inv.getArgument(0);
            return Mono.just(new CreatorProfileDto(
                    UUID.randomUUID(), in.householdId(), in.ownerId(), in.niche(), in.audience(),
                    in.tone(), in.platforms(), in.goals(), in.guardrails(), in.notes(), Instant.now()));
        });

        var msg = GoldenLlm.message(household, user,
                "моя ниша — английский для IT, аудитория — джуны-разработчики, тон дружелюбный, площадки YouTube и Telegram");

        var resp = profiler.setProfile(msg).block(Duration.ofSeconds(120));
        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();

        // Reaching the write means the model produced a parseable profile with the contract shape.
        verify(profiles, times(1)).set(captor.capture());
        SetCreatorProfileInput saved = captor.getValue();
        assertThat(saved.niche())
                .as("model produced no usable niche (degraded reply: %s)", resp.text())
                .isNotBlank();
        boolean hasOtherField = (saved.audience() != null && !saved.audience().isBlank())
                || (saved.tone() != null && !saved.tone().isBlank());
        assertThat(hasOtherField)
                .as("extract had only a niche — no audience/tone, not a structured track: %s", saved)
                .isTrue();
    }
}
