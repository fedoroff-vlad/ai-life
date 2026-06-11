package dev.fedorov.ailife.agents.finance.receipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fedorov.ailife.agentruntime.skill.Skill;
import dev.fedorov.ailife.agentruntime.skill.SkillRegistry;
import dev.fedorov.ailife.agents.finance.http.AccountClient;
import dev.fedorov.ailife.agents.finance.http.MediaClient;
import dev.fedorov.ailife.agents.finance.http.TransactionClient;
import dev.fedorov.ailife.contracts.agent.AgentManifest;
import dev.fedorov.ailife.contracts.agent.IntentResponse;
import dev.fedorov.ailife.contracts.agent.NormalizedMessage;
import dev.fedorov.ailife.contracts.finance.AddTransactionInput;
import dev.fedorov.ailife.contracts.finance.FinAccountDto;
import dev.fedorov.ailife.contracts.llm.LlmChannel;
import dev.fedorov.ailife.contracts.llm.LlmChatRequest;
import dev.fedorov.ailife.contracts.llm.LlmImage;
import dev.fedorov.ailife.contracts.llm.LlmMessage;
import dev.fedorov.ailife.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Turns an inbound receipt photo into a finance transaction (write-immediately MVP).
 *
 * <p>Pipeline: fetch the image bytes from media-service → ask the {@code vision} channel to
 * extract a structured draft (amount / currency / merchant / date) using the {@code receipt-parser}
 * SKILL.md → resolve a target account (first non-archived) → write the transaction via
 * mcp-finance's {@code POST /internal/transaction} → reply with what landed.
 *
 * <p><b>No confirm-before-write</b> in the MVP (owner decision, see STATUS Deferred work): the
 * draft is persisted immediately and the user is invited to correct it. Category is left null so
 * mcp-finance's one-shot {@code transaction.uncategorised} hook fires the categorizer skill.
 * Failures at any stage degrade to a friendly user-facing message rather than an exception.
 */
@Component
public class ReceiptParser {

    private static final Logger log = LoggerFactory.getLogger(ReceiptParser.class);
    private static final String SKILL_NAME = "receipt-parser";

    private final MediaClient media;
    private final AccountClient accounts;
    private final TransactionClient transactions;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final AgentManifest manifest;
    private final ObjectMapper json;

    public ReceiptParser(MediaClient media,
                         AccountClient accounts,
                         TransactionClient transactions,
                         LlmClient llm,
                         SkillRegistry skills,
                         AgentManifest manifest,
                         ObjectMapper json) {
        this.media = media;
        this.accounts = accounts;
        this.transactions = transactions;
        this.llm = llm;
        this.skills = skills;
        this.manifest = manifest;
        this.json = json;
    }

    public Mono<IntentResponse> parse(NormalizedMessage msg, String mediaId) {
        String skillBody = skillBody();
        return media.fetch(mediaId)
                .flatMap(img -> {
                    String base64 = Base64.getEncoder().encodeToString(img.bytes());
                    LlmChatRequest req = LlmChatRequest.of(LlmChannel.VISION, List.of(
                            LlmMessage.system(manifest.body()),
                            LlmMessage.system(skillBody),
                            LlmMessage.userWithImages(
                                    captionOr(msg.text()),
                                    List.of(new LlmImage(img.mimeType(), base64)))));
                    return llm.chat(req).flatMap(resp -> persist(msg, resp.content(), resp.model()));
                })
                .onErrorResume(e -> {
                    log.warn("receipt parse failed for media {}: {}", mediaId, e.toString());
                    return Mono.just(reply(
                            "Не удалось обработать фото чека. Попробуйте ещё раз или запишите вручную.",
                            null));
                });
    }

    private Mono<IntentResponse> persist(NormalizedMessage msg, String llmContent, String model) {
        Draft draft = parseDraft(llmContent);
        if (draft == null || draft.amount() == null) {
            return Mono.just(reply(
                    "Не удалось распознать сумму на чеке. Пришлите фото почётче или запишите вручную.",
                    model));
        }
        return accounts.list(msg.householdId()).flatMap(list -> {
            Optional<FinAccountDto> account = list.stream()
                    .filter(a -> !a.archived())
                    .findFirst();
            if (account.isEmpty()) {
                return Mono.just(reply(
                        "Сначала добавьте счёт, чтобы я мог записать расход с чека.", model));
            }
            AddTransactionInput input = buildInput(msg, draft, account.get());
            return transactions.add(input)
                    .map(saved -> reply(successText(saved.amount(), saved.currency(),
                            input.note(), account.get().name()), model))
                    .onErrorResume(e -> {
                        log.warn("add_transaction from receipt failed: {}", e.toString());
                        return Mono.just(reply(
                                "Распознал чек, но не смог записать транзакцию. Попробуйте позже.", model));
                    });
        });
    }

    private AddTransactionInput buildInput(NormalizedMessage msg, Draft draft, FinAccountDto account) {
        // Receipt = expense → store a negative amount regardless of the sign the LLM returned.
        BigDecimal amount = draft.amount().abs().negate();
        String note = (draft.merchant() != null && !draft.merchant().isBlank())
                ? draft.merchant()
                : blankToNull(draft.note());
        return new AddTransactionInput(
                msg.householdId(),
                account.id(),
                null,                       // categoryId null → categorizer one-shot fires
                null,                       // ownerId null → household-shared (MVP)
                amount,
                blankToNull(draft.currency()),  // null → mcp-finance defaults to account currency
                parseTs(draft.date()),          // null → mcp-finance defaults to now
                note,
                "telegram",
                null);
    }

    private String skillBody() {
        return skills.all().stream()
                .filter(s -> SKILL_NAME.equals(s.name()))
                .map(Skill::body)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "receipt-parser SKILL.md not loaded — check skills-classpath"));
    }

    /** Lenient JSON extraction: tolerate markdown fences / leading prose around the object. */
    private Draft parseDraft(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(content.substring(start, end + 1));
            if (!node.isObject() || node.hasNonNull("error")) {
                return null;
            }
            BigDecimal amount = node.hasNonNull("amount") ? node.get("amount").decimalValue() : null;
            return new Draft(
                    amount,
                    text(node, "currency"),
                    text(node, "merchant"),
                    text(node, "date"),
                    text(node, "note"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Instant parseTs(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String successText(BigDecimal amount, String currency, String note, String accountName) {
        String merchant = (note == null || note.isBlank()) ? "расход" : note;
        return "Добавил: " + merchant + " " + amount + " " + currency
                + " (счёт «" + accountName + "»). Категорию предложу отдельно — поправьте, если неверно.";
    }

    private static String captionOr(String text) {
        return (text == null || text.isBlank()) ? "Receipt photo — extract the transaction." : text;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private IntentResponse reply(String text, String model) {
        return new IntentResponse(manifest.name(), text, model);
    }

    private record Draft(BigDecimal amount, String currency, String merchant, String date, String note) {
    }
}
