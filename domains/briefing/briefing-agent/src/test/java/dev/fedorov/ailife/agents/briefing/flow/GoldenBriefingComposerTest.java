package dev.fedorov.ailife.agents.briefing.flow;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.briefing.http.BriefingProfileClient;
import dev.fedorov.ailife.agents.briefing.http.CalendarEventsClient;
import dev.fedorov.ailife.agents.briefing.http.FinanceSnapshotClient;
import dev.fedorov.ailife.agents.briefing.http.ForecastClient;
import dev.fedorov.ailife.agents.briefing.http.NewsSearchClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.briefing.BriefingProfileDto;
import dev.fedorov.ailife.contracts.calendar.CalendarEventDto;
import dev.fedorov.ailife.contracts.finance.SpendingByCategoryRow;
import dev.fedorov.ailife.contracts.weather.Weather;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises the briefing {@code briefing-composer} <b>synthesis
 * skill</b> against a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway),
 * asserting <b>structure, not text</b> (roadmap §Risks). Given a fixed, pre-gathered corpus (the
 * cheap-first gather is mocked), the real model must write a grounded morning briefing whose every cited
 * link is a corpus link — the failure mode that matters for a briefing agent is a fabricated headline or
 * a hallucinated source. Sibling of {@code GoldenResearchSynthesisTest} (free-text synthesis) and
 * {@link dev.fedorov.ailife.agents.briefing.profile.GoldenBriefingProfileTest} (the JSON extract).
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); CI default = unset, so the
 * suite stays green on the mock provider. The gather clients are mocked to supply a fixed corpus; the
 * real {@link Coordinator} runs the one synthesis hop over the real AGENT.md + {@code briefing-composer}
 * SKILL.md. We assert the structure of the model's briefing (grounded, no foreign links), never wording.
 */
@GoldenLlmTest
class GoldenBriefingComposerTest {

    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]<>\"'`]+");
    private static final String NEWS_URL = "https://example.com/ai-news";

    private final ObjectMapper json = new ObjectMapper();
    private final Coordinator coordinator = new Coordinator(GoldenLlm.client(), json);
    private final BriefingProfileClient profiles = mock(BriefingProfileClient.class);
    private final ForecastClient forecast = mock(ForecastClient.class);
    private final CalendarEventsClient calendar = mock(CalendarEventsClient.class);
    private final FinanceSnapshotClient finance = mock(FinanceSnapshotClient.class);
    private final NewsSearchClient news = mock(NewsSearchClient.class);
    private final DeliverablePublisher publisher = mock(DeliverablePublisher.class);
    private final AgentManifest manifest = new AgentManifest(
            "briefing", "briefing agent", "0.1.0", 8115,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenBriefingComposerTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenBriefingComposerTest.class.getClassLoader(),
                    "skills/briefing/briefing-composer/SKILL.md")));
    private final BriefingComposer composer =
            new BriefingComposer(coordinator, profiles, forecast, calendar, finance, news, publisher, skills, manifest, json);

    /**
     * STRUCTURE — the real model, given the real composer prompt and a concrete four-section corpus, must
     * write a non-trivial briefing that cites at least one source link and whose every cited link is a
     * corpus url (the "never fabricate a source" rule). Checks provenance + that it grounded, never wording.
     */
    @Test
    void composesGroundedBriefingCitingOnlyCorpusLinks() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        when(profiles.get(any(), any())).thenReturn(Mono.just(new BriefingProfileDto(
                UUID.randomUUID(), household, user, "Москва", 55.75, 37.62, "Europe/Moscow",
                json.valueToTree(List.of("AI")),
                json.valueToTree(List.of("weather", "agenda", "finance", "news")),
                "08:00", true, null, Instant.now())));
        when(forecast.forecast(anyDouble(), anyDouble())).thenReturn(Mono.just(new Weather(
                55.75, 37.62, "2026-07-02", 26.0, 16.0, 20, 15.0, 2, "Partly cloudy")));
        when(calendar.eventsBetween(any(), any(), any())).thenReturn(Mono.just(List.of(new CalendarEventDto(
                UUID.randomUUID(), household, "personal", "uid-1", "Standup", null, "Zoom",
                Instant.parse("2026-07-02T07:00:00Z"), Instant.parse("2026-07-02T07:15:00Z"),
                null, List.of(), null))));
        when(finance.spendingByCategory(any(), any(), any())).thenReturn(Mono.just(List.of(
                new SpendingByCategoryRow(UUID.randomUUID(), "Groceries", "RUB", new BigDecimal("1234.50"), 3))));
        when(news.search(anyString(), anyInt())).thenReturn(Mono.just(new WebSearchResult("AI", List.of(
                new WebSearchHit("Новый ИИ-прорыв", NEWS_URL, "Исследователи представили новую модель.")))));
        // The board store is out of scope here — fail it so the reply is the pure model synthesis, whose
        // link provenance we assert (the happy-path board link is covered by BriefingComposerTest).
        when(publisher.publish(any(), any(), any())).thenReturn(Mono.error(new RuntimeException("media off in golden")));

        IntentResponse resp = composer.digest(GoldenLlm.message(household, user, "собери мне брифинг на сегодня"))
                .block(Duration.ofSeconds(150));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        assertThat(resp.text()).as("empty briefing").isNotBlank();
        assertThat(resp.text().length()).as("briefing is implausibly short: %s", resp.text()).isGreaterThan(60);

        List<String> cited = extractUrls(resp.text());
        assertThat(cited).as("briefing cited no source link at all:\n%s", resp.text()).isNotEmpty();
        for (String url : cited) {
            assertThat(isCorpusUrl(url))
                    .as("hallucinated link '%s' (not in the corpus) in:\n%s", url, resp.text())
                    .isTrue();
        }
    }

    private List<String> extractUrls(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        Matcher m = URL.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(stripTrailing(m.group()));
        }
        return out;
    }

    /** Markdown often trails a URL with punctuation (`url).`, `url,`) — trim it before comparing. */
    private static String stripTrailing(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?)]>\"'`".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    /** Tolerate a cited link that is the corpus url with a trailing fragment, or a trimmed prefix of it. */
    private static boolean isCorpusUrl(String cited) {
        return cited.equals(NEWS_URL) || cited.startsWith(NEWS_URL) || NEWS_URL.startsWith(cited);
    }
}
