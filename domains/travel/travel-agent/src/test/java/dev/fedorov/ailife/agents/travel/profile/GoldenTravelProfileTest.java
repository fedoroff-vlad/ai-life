package dev.fedorov.ailife.agents.travel.profile;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.profile.PersonalizationProfiler;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agentruntime.http.GeocodeClient;
import dev.fedorov.ailife.agents.travel.http.TravelProfileClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.travel.SetTravelProfileInput;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden test (#199) — exercises the {@code travel-profiler} <b>JSON-extract skill</b> against a
 * <b>real model</b> (local Ollama via a running llm-gateway), asserting <b>structure, not text</b>
 * (roadmap §Risks): given a typed travel-preferences message, the real model must emit a preferences
 * object the production {@link TravelProfiler} parses into a {@link SetTravelProfileInput} with a
 * home-base plus at least one other extracted field, and any rest types must be in the vocabulary
 * (the profiler's filter is the safety net, so this proves the model + filter together never store a
 * bogus kind).
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); see
 * {@code platform/llm-gateway/README.md} §Golden tests. {@link TravelProfileClient} (the write) and
 * {@link GeocodeClient} (the geocode) are mocked; we capture the {@link SetTravelProfileInput} the
 * production parser built from the real model's reply and assert its structure, never the wording.
 */
@GoldenLlmTest
class GoldenTravelProfileTest {

    private static final Set<String> REST_TYPES =
            Set.of("beach", "active", "family", "couple", "city", "ski", "wellness");

    private final ObjectMapper json = new ObjectMapper();
    private final TravelProfileClient profiles = mock(TravelProfileClient.class);
    private final GeocodeClient geocode = mock(GeocodeClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "travel", "travel agent", "0.1.0", 8124,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenTravelProfileTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenTravelProfileTest.class.getClassLoader(),
                    "skills/travel/travel-profiler/SKILL.md")));
    private final PersonalizationProfiler template =
            new PersonalizationProfiler(GoldenLlm.client(), manifest, skills, json);
    private final TravelProfiler profiler = new TravelProfiler(template, profiles, geocode, json);

    /**
     * STRUCTURE — the real model, given the real profiler prompt and a concrete travel-preferences
     * message, must emit a preferences object the production parser turns into a
     * {@link SetTravelProfileInput} with a non-blank home-base plus at least one other extracted field
     * (rest types / companions / budget), and every stored rest type must be in the vocabulary. Checks
     * the extract's shape, never the wording.
     */
    @Test
    void extractsStructuredTravelPreferences() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(geocode.geocode(any(), any())).thenReturn(Mono.just(
                new GeoLocation("Moscow", "Russia", 55.75, 37.62, "Europe/Moscow")));
        ArgumentCaptor<SetTravelProfileInput> captor = ArgumentCaptor.forClass(SetTravelProfileInput.class);
        when(profiles.set(any(SetTravelProfileInput.class))).thenAnswer(inv -> {
            SetTravelProfileInput in = inv.getArgument(0);
            return Mono.just(new TravelProfileDto(
                    UUID.randomUUID(), in.householdId(), in.ownerId(), in.homeBaseLabel(),
                    in.homeBaseLatitude(), in.homeBaseLongitude(), in.restTypes(), in.companions(),
                    in.childAges(), in.budgetAmount(), in.budgetCurrency(), in.notes(), Instant.now()));
        });

        var msg = GoldenLlm.message(household, user,
                "мои предпочтения для путешествий: мы семья с ребёнком 4 года, любим пляжный отдых, летаем из Москвы, бюджет тысяч 200");

        var resp = profiler.setProfile(msg).block(Duration.ofSeconds(120));
        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();

        // Reaching the write means the model produced a parseable preferences object.
        verify(profiles, times(1)).set(captor.capture());
        SetTravelProfileInput saved = captor.getValue();
        assertThat(saved.homeBaseLabel())
                .as("model produced no usable home base (degraded reply: %s)", resp.text())
                .isNotBlank();
        boolean hasOtherField = (saved.restTypes() != null && saved.restTypes().size() > 0)
                || (saved.companions() != null && !saved.companions().isBlank())
                || (saved.budgetAmount() != null);
        assertThat(hasOtherField)
                .as("extract had only a home base — no rest types/companions/budget, not a structured profile: %s", saved)
                .isTrue();
        // Every stored rest type is in the fixed vocabulary (model + profiler filter never store a bogus kind).
        if (saved.restTypes() != null) {
            saved.restTypes().forEach(n ->
                    assertThat(REST_TYPES).as("out-of-vocabulary rest type stored: %s", n).contains(n.asString()));
        }
    }
}
