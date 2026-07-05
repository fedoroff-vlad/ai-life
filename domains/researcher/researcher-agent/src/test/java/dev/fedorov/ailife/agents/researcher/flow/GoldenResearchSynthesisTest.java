package dev.fedorov.ailife.agents.researcher.flow;

import tools.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.researcher.config.ResearcherAgentProperties;
import dev.fedorov.ailife.agents.researcher.http.PageFetchClient;
import dev.fedorov.ailife.agents.researcher.http.WebSearchClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.web.PageContent;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import dev.fedorov.ailife.golden.GoldenLlm;
import dev.fedorov.ailife.golden.GoldenLlmTest;
import dev.fedorov.ailife.llm.LlmClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5 <b>golden test</b> (#199) — exercises the researcher's {@code research} <b>synthesis skill</b>
 * against a <b>real model</b> (local Ollama {@code qwen2.5:7b} via a running llm-gateway), asserting
 * <b>structure, not text</b> (roadmap §Risks). Where the inbox-clarify golden test covers a JSON skill,
 * this covers a <b>free-text synthesis</b> skill and its hardest contract rule: <i>"never invent URLs —
 * every link you give must be a url from the sources"</i>. Given a fixed, pre-gathered corpus (the
 * cheap-first retrieval is mocked), the real model must write a grounded answer that cites <b>only</b>
 * corpus links — the failure mode that matters for a research agent is a hallucinated source.
 *
 * <p><b>Opt-in / gated.</b> Skipped unless {@code GOLDEN_LLM} is set (CI default = unset, so the suite
 * stays green on the mock provider without a model). To run it:
 * <pre>
 *   # 1. a real model — local Ollama with qwen2.5:7b pulled (see project memory / llm-gateway README)
 *   # 2. a llm-gateway pointed at it (raise the timeout — synthesis over a corpus is a long generation):
 *   LLM_PROVIDER=openai-compatible LLM_BASE_URL=http://localhost:11434/v1 \
 *   LLM_DEFAULT_MODEL=qwen2.5:7b LLM_REQUEST_TIMEOUT_SECONDS=180 LLM_GATEWAY_PORT=8081 \
 *     mvn -q -pl platform/llm-gateway spring-boot:run
 *   # 3. the test, pointed at the gateway:
 *   GOLDEN_LLM=true GOLDEN_LLM_GATEWAY_URL=http://localhost:8081 \
 *     mvn -q -pl domains/researcher/researcher-agent -Dtest=GoldenResearchSynthesisTest test
 * </pre>
 *
 * <p>{@link WebSearchClient} (search) and {@link PageFetchClient} (fetch) are mocked to supply a fixed
 * corpus; the real {@link Coordinator} runs the one synthesis hop over the real AGENT.md + research
 * SKILL.md. We assert the structure of the model's answer (grounded, no foreign links), never wording.
 */
@GoldenLlmTest
class GoldenResearchSynthesisTest {

    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]<>\"'`]+");

    private final ObjectMapper json = new ObjectMapper();
    private final LlmClient llm = GoldenLlm.client();
    private final Coordinator coordinator = new Coordinator(llm, json);
    private final WebSearchClient search = mock(WebSearchClient.class);
    private final PageFetchClient fetcher = mock(PageFetchClient.class);
    private final AgentManifest manifest = new AgentManifest(
            "researcher", "researcher agent", "0.1.0", 8099,
            List.of(), List.of(),
            List.<Map<String, String>>of(), List.<Map<String, String>>of(),
            GoldenLlm.agentBody(GoldenResearchSynthesisTest.class.getClassLoader()));
    private final SkillRegistry skills = new SkillRegistry(List.of(
            GoldenLlm.skill(GoldenResearchSynthesisTest.class.getClassLoader(), "skills/researcher/research/SKILL.md")));
    private final ResearcherAgentProperties props = new ResearcherAgentProperties();
    private final Researcher researcher =
            new Researcher(coordinator, search, fetcher, skills, manifest, json, props);

    // The fixed corpus: three articles (fetched in full) + one video (snippet-only).
    private static final String A1 = "https://all3dp.com/2/3d-printer-bed-leveling/";
    private static final String A2 = "https://help.prusa3d.com/article/bed-leveling";
    private static final String A3 = "https://reddit.com/r/3Dprinting/comments/leveling-tips";
    private static final String VIDEO = "https://www.youtube.com/watch?v=abc123";
    private static final Set<String> CORPUS_URLS = Set.of(A1, A2, A3, VIDEO);

    /**
     * STRUCTURE — the real model, given the real synthesis prompt and a concrete corpus, must write a
     * non-trivial answer whose every cited link is a corpus url (the "never invent URLs" rule) and that
     * grounds in the sources (at least one corpus link cited). This is the "parseable/contract-shaped
     * output" assertion for a free-text skill — it checks link provenance, never wording.
     */
    @Test
    void synthesisCitesOnlyCorpusLinks() {
        var hits = List.of(
                new WebSearchHit("3D Printer Bed Leveling: All You Need to Know", A1,
                        "A complete guide to manual and automatic bed leveling."),
                new WebSearchHit("Bed leveling — Prusa Knowledge Base", A2,
                        "Step-by-step first-layer calibration with the paper method."),
                new WebSearchHit("Bed leveling tips", A3,
                        "Community tips: clean the bed, check the springs, use a feeler gauge."),
                new WebSearchHit("Perfect Bed Leveling in 5 minutes", VIDEO,
                        "Video walkthrough of cold leveling in three passes."));
        when(search.search(anyString(), anyInt()))
                .thenReturn(Mono.just(new WebSearchResult("3d printer bed leveling", hits)));
        when(fetcher.fetch(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            return Mono.just(new PageContent(url, "page", pageText(url), false));
        });

        Researcher.ResearchResult r = researcher.research(
                GoldenLlm.message("найди как откалибровать стол 3D-принтера и дай пару ссылок"))
                .block(Duration.ofSeconds(150));

        assertThat(r).as("null result — is llm-gateway up at %s?", GoldenLlm.gatewayUrl()).isNotNull();
        assertThat(r.text()).as("empty synthesis").isNotBlank();
        assertThat(r.text().length()).as("synthesis is implausibly short: %s", r.text()).isGreaterThan(60);

        List<String> cited = extractUrls(r.text());
        assertThat(cited)
                .as("synthesis cited no source links at all:\n%s", r.text())
                .isNotEmpty();
        for (String url : cited) {
            assertThat(isCorpusUrl(url))
                    .as("hallucinated link '%s' (not in the corpus) in:\n%s", url, r.text())
                    .isTrue();
        }
    }

    /** A short readable body per article so the model has something concrete to ground in. */
    private static String pageText(String url) {
        if (A1.equals(url)) {
            return "Bed leveling means setting an even gap between the nozzle and the bed across the "
                    + "whole surface. Heat the bed and nozzle to printing temperature first, then adjust "
                    + "the corner screws using the paper-drag test.";
        }
        if (A2.equals(url)) {
            return "Use Live Adjust Z while printing the first layer: lower the nozzle until the lines "
                    + "fuse together with no gaps. Re-check after the bed reaches 60°C.";
        }
        return "Clean the bed with isopropyl alcohol, inspect the bed springs for play, and use a "
                + "feeler gauge of 0.1 mm at each corner for a repeatable gap.";
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

    /** Tolerate a cited link that is a corpus url with a trailing fragment, or a trimmed prefix of one. */
    private static boolean isCorpusUrl(String cited) {
        return CORPUS_URLS.stream().anyMatch(c -> cited.equals(c)
                || cited.startsWith(c) || c.startsWith(cited));
    }

}
