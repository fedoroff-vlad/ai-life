package dev.fedorov.ailife.agents.researcher.flow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.fedorov.ailife.agentruntime.coordinate.Coordinator;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.researcher.config.ResearcherAgentProperties;
import dev.fedorov.ailife.agents.researcher.http.PageFetchClient;
import dev.fedorov.ailife.agents.researcher.http.WebSearchClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.web.PageContent;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The cheap-first research flow (R-d): the researcher's reason for existing. On a research intent
 * it (1) <b>searches</b> the web via {@code mcp-web} {@code /internal/search}, (2) <b>reads</b> the
 * top non-video hits in full via {@code /internal/fetch} (in parallel, soft-failing per page; video
 * hits are skipped — their pages are boilerplate, so the search snippet describes them), then (3)
 * folds the gathered corpus into a {@code context} and runs <b>one</b> LLM synthesis on the shared
 * {@link Coordinator} from {@code [AGENT.md, research SKILL.md] + {payload(userText), context}}.
 *
 * <p><b>Token economy is structural:</b> steps 1–2 are plain HTTP (no model cost); only step 3 hits
 * the LLM, summarizing a pre-selected corpus rather than "browsing". Search → fetch is sequential
 * (fetch needs the hit URLs), so unlike a parallel-gather flow the retrieval is done here and the
 * Coordinator is used for the synthesis. Every stage degrades to a friendly message on failure.
 */
@Component
public class Researcher {

    private static final Logger log = LoggerFactory.getLogger(Researcher.class);
    private static final String SKILL_NAME = "research";
    /**
     * Video hosts whose pages return only boilerplate via {@code fetch_url} (JS-rendered) — we skip
     * fetching them and let the search snippet supply the short description + the link. (Reading a
     * video's actual content is {@code mcp-web}'s {@code transcribe_video} tool, used by a future
     * "summarise this video" flow, not the default research path.)
     */
    private static final Set<String> VIDEO_HOSTS = Set.of(
            "youtube.com", "youtu.be", "vimeo.com", "tiktok.com",
            "rutube.ru", "dailymotion.com", "instagram.com/reel");

    private final Coordinator coordinator;
    private final WebSearchClient search;
    private final PageFetchClient fetcher;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;
    private final ResearcherAgentProperties props;

    public Researcher(Coordinator coordinator,
                      WebSearchClient search,
                      PageFetchClient fetcher,
                      SkillRegistry skills,
                      AgentManifest manifest,
                      ObjectMapper json,
                      ResearcherAgentProperties props) {
        this.coordinator = coordinator;
        this.search = search;
        this.fetcher = fetcher;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
        this.props = props;
    }

    public Mono<ResearchResult> research(NormalizedMessage msg) {
        String query = msg == null ? null : msg.text();
        if (query == null || query.isBlank()) {
            return Mono.just(new ResearchResult("Что именно поискать? Уточните запрос.", null));
        }
        return search.search(query, props.getSearchLimit())
                .onErrorResume(e -> {
                    log.warn("web_search failed for '{}': {}", query, e.toString());
                    return Mono.just(new WebSearchResult(query, List.of()));
                })
                .flatMap(result -> {
                    List<WebSearchHit> hits = result.hits() == null ? List.of() : result.hits();
                    List<WebSearchHit> top = hits.stream().limit(props.getFetchTopN()).toList();
                    return fetchPages(top).flatMap(pages -> synthesize(query, hits, pages));
                })
                .onErrorResume(e -> {
                    log.warn("research failed for '{}': {}", query, e.toString());
                    return Mono.just(new ResearchResult(
                            "Не смог выполнить поиск. Попробуйте позже.", null));
                });
    }

    /**
     * Fetch the top non-video hits in parallel; a page that errors or comes back blank is dropped.
     * Video hits are skipped — their pages are boilerplate-only, so the snippet (kept in the corpus
     * by {@link #synthesize}) is what describes them. The user wants a link + 1–2 sentences for a
     * video, not its full transcript.
     */
    private Mono<Map<String, String>> fetchPages(List<WebSearchHit> top) {
        return Flux.fromIterable(top)
                .filter(hit -> !isVideoUrl(hit.url()))
                .flatMap(hit -> fetcher.fetch(hit.url())
                        .map(PageContent::text)
                        .onErrorResume(e -> {
                            log.warn("fetch_url failed for {}: {}", hit.url(), e.toString());
                            return Mono.empty();
                        })
                        .filter(text -> text != null && !text.isBlank())
                        .map(text -> Map.entry(hit.url(), text)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /** Fold the search hits + fetched page text into a corpus and synthesize one answer. */
    private Mono<ResearchResult> synthesize(String query, List<WebSearchHit> hits, Map<String, String> pages) {
        ObjectNode corpus = json.createObjectNode();
        corpus.put("query", query);
        ArrayNode sources = corpus.putArray("sources");
        for (WebSearchHit h : hits) {
            ObjectNode s = sources.addObject();
            if (h.title() != null) s.put("title", h.title());
            s.put("url", h.url());
            if (h.snippet() != null) s.put("snippet", h.snippet());
            String text = pages.get(h.url());
            if (text != null && !text.isBlank()) s.put("text", text);
        }

        ObjectNode payload = json.createObjectNode();
        payload.put("userText", query);

        Map<String, Mono<JsonNode>> gather = new LinkedHashMap<>();
        gather.put("web", Mono.just(corpus));

        return coordinator.coordinate(
                        List.of(manifest.body(), skillBody()),
                        payload,
                        gather,
                        LlmChannel.DEFAULT)
                .map(r -> new ResearchResult(r.text(), r.llmModel()))
                .onErrorResume(e -> {
                    log.warn("research synthesis failed for '{}': {}", query, e.toString());
                    return Mono.just(new ResearchResult(
                            "Нашёл источники, но не смог собрать выжимку. Попробуйте позже.", null));
                });
    }

    private static boolean isVideoUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        return VIDEO_HOSTS.stream().anyMatch(u::contains);
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "research SKILL.md not loaded — check skills-classpath"));
    }

    /** The synthesized answer text plus the model that produced it (for the response contract). */
    public record ResearchResult(String text, String model) {
    }
}
