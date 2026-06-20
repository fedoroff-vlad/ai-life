package dev.fedorov.ailife.contracts.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One result of the {@code mcp-web} {@code web_search} tool: a title, the URL, and a short
 * snippet the engine returned. The capability returns links + snippets only — reading a page
 * in full is {@code fetch_url}, and synthesizing an answer is the calling agent's job.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebSearchHit(
        String title,
        String url,
        String snippet) {
}
