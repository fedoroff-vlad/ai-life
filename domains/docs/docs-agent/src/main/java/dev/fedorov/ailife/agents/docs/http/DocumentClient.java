package dev.fedorov.ailife.agents.docs.http;

import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.docs.SaveDocumentInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Calls the {@code mcp-docs} domain-MCP's {@code /internal/documents} passthroughs to archive
 * (doc-archiver, D-c) and search (doc-finder, D-d) documents. The flows have already extracted a
 * concrete input, so they act deterministically over HTTP rather than through an LLM-driven MCP tool
 * call. Mirrors briefing-agent's {@code BriefingProfileClient}.
 */
@Component
public class DocumentClient {

    private final WebClient http;

    public DocumentClient(@Qualifier("mcpDocsWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<DocumentDto> save(SaveDocumentInput input) {
        return http.post()
                .uri("/internal/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(DocumentDto.class)
                .timeout(Duration.ofSeconds(10));
    }

    /** One archived document by id (D-e: resolving a semantic-recall hit's {@code refId}). */
    public Mono<DocumentDto> get(UUID id) {
        return http.get()
                .uri("/internal/documents/{id}", id)
                .retrieve()
                .bodyToMono(DocumentDto.class)
                .timeout(Duration.ofSeconds(10));
    }

    /** Free-text search over a household's documents (D-d); an empty result is an empty list. */
    public Mono<List<DocumentDto>> search(UUID householdId, String query, String docType, Integer limit) {
        return http.get()
                .uri(b -> {
                    b.path("/internal/documents/search")
                            .queryParam("householdId", householdId)
                            .queryParam("query", query);
                    if (docType != null && !docType.isBlank()) b.queryParam("docType", docType);
                    if (limit != null) b.queryParam("limit", limit);
                    return b.build();
                })
                .retrieve()
                .bodyToFlux(DocumentDto.class)
                .collectList()
                .timeout(Duration.ofSeconds(10));
    }
}
