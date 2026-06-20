package dev.fedorov.ailife.contracts.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result of the {@code mcp-web} {@code fetch_url} tool: the readable text extracted from a page
 * (boilerplate like scripts / nav / footers stripped), its {@code title}, and a {@code truncated}
 * flag set when the text was capped. {@code text} is empty (never null) when the page couldn't be
 * read — cheap retrieval, no LLM; the calling agent summarizes the text.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageContent(
        String url,
        String title,
        String text,
        boolean truncated) {
}
