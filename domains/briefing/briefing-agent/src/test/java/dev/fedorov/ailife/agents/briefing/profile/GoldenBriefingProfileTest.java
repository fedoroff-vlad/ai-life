package dev.fedorov.ailife.agents.briefing.profile;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.briefing.http.BriefingProfileClient;
import dev.fedorov.ailife.agents.briefing.http.GeocodeClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.briefing.SetBriefingProfileInput;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
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
 * Stage 5 <b>golden test</b> (#199) — exercises the briefing {@code briefing-profiler} <b>JSON-extract
 * skill</b> against a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway),
 * asserting <b>structure, not text</b> (roadmap §Risks): given a typed briefing config, the real model
 * must emit a preferences object the production {@link BriefingProfiler} parses into a
 * {@link SetBriefingProfileInput} with a location plus at least one other extracted field.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); see
 * {@code platform/llm-gateway/README.md} §Golden tests. {@link BriefingProfileClient} (the write) and
 * {@link GeocodeClient} (the geocode) are mocked; we capture the {@link SetBriefingProfileInput} the
 * production parser built from the real model's reply and assert its structure, never the wording.
 */
@GoldenLlmTest
class GoldenBriefingProfileTest {

    private final ObjectMapper json = new ObjectMapper();
    private final BriefingProfileClient profiles = mock(BriefingProfileClient.class);
    private final GeocodeClient geocode = mock(GeocodeClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "briefing", "briefing agent", "0.1.0", 8115,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenBriefingProfileTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenBriefingProfileTest.class.getClassLoader(),
                    "skills/briefing/briefing-profiler/SKILL.md")));
    private final BriefingProfiler profiler =
            new BriefingProfiler(profiles, geocode, GoldenLlm.client(), skills, manifest, json);

    /**
     * STRUCTURE — the real model, given the real profiler prompt and a concrete briefing config, must
     * emit a preferences object the production parser turns into a {@link SetBriefingProfileInput} with
     * a non-blank location plus at least one other extracted field (interests / sections / schedule).
     * Checks the extract's shape, never the wording.
     */
    @Test
    void extractsStructuredBriefingPreferences() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(geocode.geocode(any(), any())).thenReturn(Mono.just(
                new GeoLocation("Moscow", "Russia", 55.75, 37.62, "Europe/Moscow")));
        ArgumentCaptor<SetBriefingProfileInput> captor = ArgumentCaptor.forClass(SetBriefingProfileInput.class);
        when(profiles.set(any(SetBriefingProfileInput.class))).thenAnswer(inv -> {
            SetBriefingProfileInput in = inv.getArgument(0);
            return Mono.just(new BriefingProfileDto(
                    UUID.randomUUID(), in.householdId(), in.ownerId(), in.locationLabel(), in.latitude(),
                    in.longitude(), in.timezone(), in.interests(), in.sections(), in.scheduleTime(),
                    in.scheduleEnabled(), in.notes(), Instant.now()));
        });

        var msg = GoldenLlm.message(household, user,
                "настрой мне брифинг: каждое утро в 8:00 показывай погоду в Москве, новости про ИИ и финансы, и мою повестку дня");

        var resp = profiler.setProfile(msg).block(Duration.ofSeconds(120));
        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();

        // Reaching the write means the model produced a parseable preferences object.
        verify(profiles, times(1)).set(captor.capture());
        SetBriefingProfileInput saved = captor.getValue();
        assertThat(saved.locationLabel())
                .as("model produced no usable location (degraded reply: %s)", resp.text())
                .isNotBlank();
        boolean hasOtherField = (saved.interests() != null && saved.interests().size() > 0)
                || (saved.sections() != null && saved.sections().size() > 0)
                || (saved.scheduleTime() != null && !saved.scheduleTime().isBlank());
        assertThat(hasOtherField)
                .as("extract had only a location — no interests/sections/schedule, not a structured config: %s", saved)
                .isTrue();
    }
}
