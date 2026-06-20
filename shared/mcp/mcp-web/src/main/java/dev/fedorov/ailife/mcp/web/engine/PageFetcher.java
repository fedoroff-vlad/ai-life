package dev.fedorov.ailife.mcp.web.engine;

import dev.fedorov.ailife.contracts.web.PageContent;

/**
 * Pluggable page-reader: fetch a URL and return its readable text (boilerplate stripped). The
 * default is {@link JsoupPageFetcher}; a heavier readability lib (readability4j) can replace it
 * behind this interface. Mirrors {@link SearchEngine} / {@code OcrEngine}. Best-effort: a page
 * that can't be read returns empty text rather than throwing (the caller drops it).
 */
public interface PageFetcher {

    PageContent fetch(String url);
}
