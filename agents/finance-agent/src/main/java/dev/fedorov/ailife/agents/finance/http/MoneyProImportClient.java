package dev.fedorov.ailife.agents.finance.http;

import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvInput;
import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Calls mcp-money-pro-import's {@code POST /internal/import} to run a Money Pro CSV import.
 * Used by the finance-agent CSV-attachment flow once it has fetched the bytes. A generous timeout
 * — a full history export can be thousands of rows.
 */
@Component
public class MoneyProImportClient {

    private final WebClient http;

    public MoneyProImportClient(@Qualifier("moneyProImportWebClient") WebClient http) {
        this.http = http;
    }

    public Mono<ImportMoneyProCsvResult> importCsv(ImportMoneyProCsvInput input) {
        return http.post()
                .uri("/internal/import")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(input)
                .retrieve()
                .bodyToMono(ImportMoneyProCsvResult.class)
                .timeout(Duration.ofSeconds(30));
    }
}
