package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.FinCategoryDto;
import dev.fedorov.ailife.contracts.finance.UpsertCategoryInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Reads and writes finance categories via mcp-finance's {@code /internal} passthroughs
 * ({@code GET /internal/categories}, {@code POST /internal/category}). Used by the
 * {@code category-manager} flow to list existing categories (to resolve a parent by name) and
 * create/group them from chat, without an LLM-driven MCP tool call. Mirrors {@link AccountClient}
 * / {@link TransactionClient}.
 */
@Component
public class CategoryClient {

    private final WebClient http;

    public CategoryClient(@Qualifier("mcpFinanceWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<List<FinCategoryDto>> list(UUID householdId) {
        return http.get()
                .uri(uri -> uri.path("/internal/categories")
                        .queryParam("householdId", householdId)
                        .build())
                .retrieve()
                .bodyToFlux(FinCategoryDto.class)
                .collectList()
                .timeout(Duration.ofSeconds(5));
    }

    public Mono<FinCategoryDto> upsert(UpsertCategoryInput input) {
        return http.post()
                .uri("/internal/category")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(FinCategoryDto.class)
                .timeout(Duration.ofSeconds(5));
    }
}
