package dev.fedorov.ailife.agents.docs.http;

import dev.fedorov.ailife.contracts.docs.DocumentDto;
import dev.fedorov.ailife.contracts.docs.SaveDocumentInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls the {@code mcp-docs} domain-MCP's {@code POST /internal/documents} passthrough to archive a
 * document. The doc-archiver flow has already extracted a concrete {@link SaveDocumentInput}, so it
 * writes deterministically over HTTP rather than through an LLM-driven MCP tool call. Mirrors
 * briefing-agent's {@code BriefingProfileClient}. (Search is added in D-d.)
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
}
