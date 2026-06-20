package dev.fedorov.ailife.mcp.web.tools;

import dev.fedorov.ailife.contracts.web.PageContent;
import dev.fedorov.ailife.contracts.web.WebSearchHit;
import dev.fedorov.ailife.contracts.web.WebSearchResult;
import dev.fedorov.ailife.mcp.web.config.McpWebProperties;
import dev.fedorov.ailife.mcp.web.engine.PageFetcher;
import dev.fedorov.ailife.mcp.web.engine.SearchEngine;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The shared web toolbox. {@code web_search} (R-a) runs a query against the configured
 * {@link SearchEngine} (SearXNG by default) and returns ranked title/url/snippet hits.
 * {@code fetch_url} (R-b) reads a page. The capability returns cheap retrieval only — no LLM —
 * so the calling agent does the (single) synthesis. Any agent binds this server over MCP/SSE;
 * the deterministic path goes through the {@code /internal/*} HTTP passthroughs.
 */
@Component
public class WebMcpTools {

    private final SearchEngine engine;
    private final PageFetcher fetcher;
    private final McpWebProperties props;

    public WebMcpTools(SearchEngine engine, PageFetcher fetcher, McpWebProperties props) {
        this.engine = engine;
        this.fetcher = fetcher;
        this.props = props;
    }

    @Tool(description = """
            Search the web and return ranked results (title, url, snippet) for a query.
            Use this to find sources — articles, docs, videos, anything — before reading or
            summarising. Returns links + short snippets only; call 'fetch_url' to read a page
            in full, and do the summarising yourself. 'limit' caps the number of results
            (optional; a sensible default is used when omitted).
            """)
    public WebSearchResult web_search(String query, Integer limit) {
        if (query == null || query.isBlank()) {
            return new WebSearchResult(query, List.of());
        }
        int n = resolveLimit(limit);
        List<WebSearchHit> hits = engine.search(query, n).block();
        return new WebSearchResult(query, hits == null ? List.of() : hits);
    }

    @Tool(description = """
            Fetch a single web page by URL and return its readable text (boilerplate like
            navigation, scripts and footers removed), plus the page title. Use this after
            'web_search' to read a promising result in full before summarising it. Returns
            empty text if the page can't be fetched. The text may be truncated for very long
            pages (a flag indicates this). You do the summarising — this returns raw text only.
            """)
    public PageContent fetch_url(String url) {
        if (url == null || url.isBlank()) {
            return new PageContent(url, null, "", false);
        }
        return fetcher.fetch(url);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return props.getDefaultLimit();
        }
        return Math.min(limit, props.getMaxLimit());
    }
}
