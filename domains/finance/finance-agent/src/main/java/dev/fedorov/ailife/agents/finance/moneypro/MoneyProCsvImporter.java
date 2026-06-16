package dev.fedorov.ailife.agents.finance.moneypro;

import dev.fedorov.ailife.agents.finance.http.MediaClient;
import dev.fedorov.ailife.agents.finance.http.MoneyProImportClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvInput;
import dev.fedorov.ailife.contracts.finance.ImportMoneyProCsvResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Handles a CSV (document) attachment as a Money Pro history import (auto-create accounts MVP).
 *
 * <p>Fetch the bytes from media-service → base64 → call mcp-money-pro-import's
 * {@code POST /internal/import} with {@code autoCreateAccounts=true} and an empty {@code accountMap}
 * (Money Pro account names auto-create as `fin_account`s) → report the counts. There is no
 * interactive account mapping — that's deferred with the conversation-state layer (see STATUS).
 * Failures degrade to a friendly Russian message rather than an exception.
 */
@Component
public class MoneyProCsvImporter {

    private static final Logger log = LoggerFactory.getLogger(MoneyProCsvImporter.class);

    private final MediaClient media;
    private final MoneyProImportClient importClient;
    private final AgentManifest manifest;

    public MoneyProCsvImporter(MediaClient media,
                               MoneyProImportClient importClient,
                               AgentManifest manifest) {
        this.media = media;
        this.importClient = importClient;
        this.manifest = manifest;
    }

    public Mono<IntentResponse> importCsv(NormalizedMessage msg, String mediaId) {
        return media.fetch(mediaId)
                .flatMap(file -> {
                    String csvBase64 = Base64.getEncoder().encodeToString(file.bytes());
                    ImportMoneyProCsvInput input = new ImportMoneyProCsvInput(
                            msg.householdId(), csvBase64, null,
                            Map.of(), null, "telegram-upload", false, true);
                    return importClient.importCsv(input);
                })
                .map(result -> reply(formatResult(result)))
                .onErrorResume(e -> {
                    log.warn("Money Pro CSV import failed for media {}: {}", mediaId, e.toString());
                    return Mono.just(reply(
                            "Не удалось импортировать CSV. Проверьте, что это экспорт из Money Pro."));
                });
    }

    private String formatResult(ImportMoneyProCsvResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Импортировал ").append(r.created()).append(" транзакций");
        if (r.skipped() > 0) {
            sb.append(" (пропущено ").append(r.skipped()).append(" дублей)");
        }
        sb.append('.');
        if (r.errored() > 0) {
            sb.append(" Ошибок в строках: ").append(r.errored()).append('.');
        }
        List<String> created = r.createdAccounts();
        if (created != null && !created.isEmpty()) {
            sb.append(" Создал счета: ").append(String.join(", ", created)).append('.');
        }
        return sb.toString();
    }

    private IntentResponse reply(String text) {
        // No LLM round-trip on this path → no model id.
        return new IntentResponse(manifest.name(), text, null);
    }
}
