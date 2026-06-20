package dev.fedorov.ailife.mcp.web.engine;

import dev.fedorov.ailife.contracts.web.WebSearchHit;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Pluggable web-search backend. The default is {@link SearxngSearchEngine} (self-hosted,
 * free); Tavily/Brave can replace it later via {@code mcp-web.search-engine} with no caller
 * change. Mirrors {@code mcp-media-processing}'s {@code OcrEngine}. Returns ranked hits in
 * engine order, trimmed to {@code limit}.
 */
public interface SearchEngine {

    Mono<List<WebSearchHit>> search(String query, int limit);
}
