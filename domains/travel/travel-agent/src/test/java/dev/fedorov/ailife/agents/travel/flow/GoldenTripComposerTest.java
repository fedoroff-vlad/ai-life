package dev.fedorov.ailife.agents.travel.flow;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.deliver.DeliverablePublisher;
import dev.fedorov.ailife.agentruntime.http.OrchestratorInvokeClient;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agentruntime.http.ChartRenderClient;
import dev.fedorov.ailife.agents.travel.http.ClimateClient;
import dev.fedorov.ailife.agentruntime.http.GeocodeClient;
import dev.fedorov.ailife.agents.travel.http.TravelProfileClient;
import dev.fedorov.ailife.agents.travel.http.TravelSearchClient;
import dev.fedorov.ailife.agentruntime.http.WebSearchClient;
import dev.fedorov.ailife.contracts.agent.AgentActionResult;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.travel.TravelProfileDto;
import dev.fedorov.ailife.contracts.travelsearch.FlightOffer;
import dev.fedorov.ailife.contracts.travelsearch.FlightSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.HotelOffer;
import dev.fedorov.ailife.contracts.travelsearch.HotelSearchResult;
import dev.fedorov.ailife.contracts.travelsearch.PlaceResult;
import dev.fedorov.ailife.contracts.weather.ClimateNormals;
import dev.fedorov.ailife.contracts.weather.GeoLocation;
import dev.fedorov.ailife.contracts.weather.MonthlyNormal;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Golden test (#199) — exercises the travel {@code trip-composer} <b>synthesis skill</b> against a
 * <b>real model</b> (local Ollama via a running llm-gateway), asserting <b>structure, not text</b>
 * (roadmap §Risks). Given a fixed, pre-gathered corpus (the cheap-first gather is mocked — a budget
 * brief, free dates, the destination's climate, and one web article), the real model must write a
 * grounded trip plan whose every cited link is a corpus link — the failure mode that matters for a
 * travel agent is a fabricated destination fact or a hallucinated (bookable-looking) source. Sibling of
 * {@code GoldenBriefingComposerTest}.
 *
 * <p><b>Opt-in / gated</b> via {@link GoldenLlmTest} ({@code GOLDEN_LLM}); CI default = unset, so the
 * suite stays green on the mock provider. The gather clients (and the hub) are mocked to supply a fixed
 * corpus; the real {@link Coordinator} runs the one synthesis hop over the real AGENT.md +
 * {@code trip-composer} SKILL.md. We assert the plan is grounded (cites a corpus link, invents no
 * foreign link), never wording.
 */
@GoldenLlmTest
class GoldenTripComposerTest {

    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]<>\"'`]+");
    private static final String RESEARCH_URL = "https://example.com/turkey-guide";
    private static final String FLIGHT_URL = "https://www.aviasales.com/search/MOW0912AYT1";
    private static final String HOTEL_URL = "https://search.hotellook.com/hotels/rixos";

    private final ObjectMapper json = new ObjectMapper();
    private final Coordinator coordinator = new Coordinator(GoldenLlm.client(), json);
    private final TravelProfileClient profiles = mock(TravelProfileClient.class);
    private final GeocodeClient geocode = mock(GeocodeClient.class);
    private final ClimateClient climate = mock(ClimateClient.class);
    private final WebSearchClient web = mock(WebSearchClient.class);
    private final TravelSearchClient search = mock(TravelSearchClient.class);
    private final OrchestratorInvokeClient hub = mock(OrchestratorInvokeClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "travel", "travel agent", "0.1.0", 8124,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenTripComposerTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenTripComposerTest.class.getClassLoader(),
                    "skills/travel/trip-composer/SKILL.md")));
    // The TR-e board seam is not under test here (the golden asserts plan grounding, not the HTML board),
    // so both soft-fail to empty → the text-only reply, keeping the corpus-links assertion clean.
    private final DeliverablePublisher publisher = mock(DeliverablePublisher.class);
    private final ChartRenderClient chartRender = mock(ChartRenderClient.class);
    private final TripComposer composer = new TripComposer(
            coordinator, profiles, geocode, climate, web, search, hub, GoldenLlm.client(), skills, manifest,
            json, publisher, chartRender);

    /**
     * STRUCTURE — the real model, given the real composer prompt and a concrete corpus (budget + dates +
     * climate + one web article), must write a non-trivial trip plan that cites at least one source link
     * and whose every cited link is the corpus url (the "never fabricate a source" rule). Checks
     * provenance + that it grounded, never wording.
     */
    @Test
    void composesGroundedPlanCitingOnlyCorpusLinks() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        when(profiles.get(any(), any())).thenReturn(Mono.just(new TravelProfileDto(
                UUID.randomUUID(), household, user, "Москва", 55.75, 37.62,
                json.valueToTree(List.of("beach")), "family", json.valueToTree(List.of(4)),
                new java.math.BigDecimal("200000"), "RUB", null, Instant.now())));
        when(geocode.geocode(any(), any())).thenReturn(Mono.just(
                new GeoLocation("Antalya", "Turkey", 36.9, 30.7, "Europe/Istanbul")));
        when(climate.climate(anyDouble(), anyDouble(), any())).thenReturn(Mono.just(new ClimateNormals(
                36.9, 30.7, List.of(new MonthlyNormal(9, 28.4, 21.0), new MonthlyNormal(1, 10.0, 190.0)))));
        when(web.search(anyString(), anyInt())).thenReturn(Mono.just(new WebSearchResult("Турция сентябрь", List.of(
                new WebSearchHit("Турция в сентябре — бархатный сезон", RESEARCH_URL,
                        "Море тёплое, погода мягкая, семьям комфортно.")))));
        when(hub.invoke(any(), any())).thenReturn(Mono.just(AgentActionResult.ok(answer(
                "Бюджет на отпуск около 250000 RUB, запас есть."))));
        when(chartRender.render(any(), any(), any())).thenReturn(Mono.empty());
        when(publisher.publish(any(), any(), any())).thenReturn(Mono.empty());

        IntentResponse resp = composer.plan(GoldenLlm.message(household, user,
                        "хочу в Турцию в сентябре, бюджет тысяч 200 на семью"))
                .block(Duration.ofSeconds(150));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        assertThat(resp.text()).as("empty plan").isNotBlank();
        assertThat(resp.text().length()).as("plan is implausibly short: %s", resp.text()).isGreaterThan(60);

        List<String> cited = extractUrls(resp.text());
        for (String url : cited) {
            assertThat(isCorpusUrl(url))
                    .as("hallucinated link '%s' (not in the corpus) in:\n%s", url, resp.text())
                    .isTrue();
        }
    }

    /**
     * STRUCTURE (TR-f2) — when the owner asks for concrete tickets/hotels, the real model must fold the
     * pre-gathered <b>live options</b> into the plan: it lists offers as the provider <b>deep links</b> and
     * never fabricates a link or a bookable price without a source. The gather (place resolve + flight/hotel
     * search) is mocked to a fixed corpus; the real Coordinator runs the one synthesis over the real SKILL.
     * Asserts every cited link is a corpus link and at least one is an offer deep link — provenance, not wording.
     */
    @Test
    void composesLivePlanCitingOnlyCorpusOptionLinks() {
        UUID household = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        when(profiles.get(any(), any())).thenReturn(Mono.just(new TravelProfileDto(
                UUID.randomUUID(), household, user, "Москва", 55.75, 37.62,
                json.valueToTree(List.of("beach")), "couple", null,
                new java.math.BigDecimal("40000"), "RUB", null, Instant.now())));
        when(geocode.geocode(any(), any())).thenReturn(Mono.just(
                new GeoLocation("Antalya", "Turkey", 36.9, 30.7, "Europe/Istanbul")));
        when(climate.climate(anyDouble(), anyDouble(), any())).thenReturn(Mono.just(new ClimateNormals(
                36.9, 30.7, List.of(new MonthlyNormal(9, 28.4, 21.0)))));
        when(web.search(anyString(), anyInt())).thenReturn(Mono.just(new WebSearchResult("Турция сентябрь", List.of(
                new WebSearchHit("Турция в сентябре", RESEARCH_URL, "Море тёплое.")))));
        when(hub.invoke(any(), any())).thenReturn(Mono.just(AgentActionResult.ok(answer(
                "Бюджет на отпуск около 40000 RUB."))));
        // Live options: two ranked flights (over/under budget) + one hotel, each a provider deep link.
        when(search.resolvePlace(anyString())).thenReturn(Mono.just(
                new PlaceResult("Турция", "Turkey", "AYT", "12209", false)));
        when(search.searchFlights(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(Mono.just(new FlightSearchResult(false, List.of(
                        new FlightOffer(30000.0, "RUB", 0, "Direct", "2026-09", null, FLIGHT_URL),
                        new FlightOffer(50000.0, "RUB", 1, "OneStop", "2026-09", null, FLIGHT_URL + "b")))));
        when(search.searchHotels(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Mono.just(new HotelSearchResult(false, List.of(
                        new HotelOffer("Rixos", 12000.0, "RUB", 5.0, HOTEL_URL)))));
        when(chartRender.render(any(), any(), any())).thenReturn(Mono.empty());
        when(publisher.publish(any(), any(), any())).thenReturn(Mono.empty());

        IntentResponse resp = composer.plan(GoldenLlm.message(household, user,
                        "найди билеты в Турцию в сентябре и подбери отель, бюджет тысяч 40"))
                .block(Duration.ofSeconds(150));

        assertThat(resp).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        assertThat(resp.text()).as("empty plan").isNotBlank();

        List<String> cited = extractUrls(resp.text());
        assertThat(cited).as("no option/source link cited in:\n%s", resp.text()).isNotEmpty();
        for (String url : cited) {
            assertThat(isLiveCorpusUrl(url))
                    .as("hallucinated link '%s' (not in the corpus) in:\n%s", url, resp.text())
                    .isTrue();
        }
        assertThat(cited).as("no offer deep link surfaced in:\n%s", resp.text())
                .anyMatch(u -> u.startsWith("https://www.aviasales.com") || u.startsWith("https://search.hotellook.com"));
    }

    /** The live golden's corpus: the research article + the offer deep links (with tolerant prefixing). */
    private static boolean isLiveCorpusUrl(String cited) {
        return isCorpusUrl(cited)
                || cited.startsWith("https://www.aviasales.com") || cited.startsWith("https://search.hotellook.com");
    }

    private ObjectNode answer(String text) {
        ObjectNode node = json.createObjectNode();
        node.put("agent", "finance");
        node.put("answer", text);
        return node;
    }

    private List<String> extractUrls(String text) {
        List<String> out = new ArrayList<>();
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
        return cited.equals(RESEARCH_URL) || cited.startsWith(RESEARCH_URL) || RESEARCH_URL.startsWith(cited);
    }
}
